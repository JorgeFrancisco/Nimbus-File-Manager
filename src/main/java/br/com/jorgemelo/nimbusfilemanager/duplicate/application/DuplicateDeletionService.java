package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeletionResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Outcome;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureFileMove;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PhysicalFilePolicy;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;
import lombok.extern.slf4j.Slf4j;

/**
 * Moves the duplicate files the user selected on the Duplicados screen into the
 * configured quarantine folder (a soft delete), recording a
 * {@code DEDUP_DELETE} execution with a {@code Movement} per file and flipping
 * each {@link CatalogFile} to {@code DELETED}. Nothing is permanently removed
 * here - a later retention job expurges the quarantine, and the whole execution
 * can be undone (files moved back, lifecycle ACTIVE). Refuses to run while the
 * quarantine folder is unconfigured.
 */
@Slf4j
@Service
public class DuplicateDeletionService extends LocalizedComponent {

	private final CatalogFileRepository catalogFileRepository;
	private final ExecutionRepository executionRepository;
	private final AppSettingService appSettingService;
	private final DuplicateDeletionPersistence duplicateDeletionPersistence;
	private final SecureFileMove secureFileMove;
	private final SimilarityCaches similarityCaches;
	private final OperationLockService operationLockService;
	private final Clock clock;

	public DuplicateDeletionService(CatalogFileRepository catalogFileRepository,
			ExecutionRepository executionRepository,
			AppSettingService appSettingService, DuplicateDeletionPersistence duplicateDeletionPersistence,
			SecureFileMove secureFileMove, SimilarityCaches similarityCaches,
			OperationLockService operationLockService, Clock clock) {
		this.catalogFileRepository = catalogFileRepository;
		this.executionRepository = executionRepository;
		this.appSettingService = appSettingService;
		this.duplicateDeletionPersistence = duplicateDeletionPersistence;
		this.secureFileMove = secureFileMove;
		this.similarityCaches = similarityCaches;
		this.operationLockService = operationLockService;
		this.clock = clock;
	}

	public DuplicateDeletionResult delete(Collection<UUID> publicIds) {
		return delete(publicIds, (_, _) -> {
		});
	}

	/**
	 * Same as {@link #delete(Collection)} but reports how many files have been
	 * processed (moved, skipped or errored) out of the total to {@code progress},
	 * so a background runner can drive a "Movendo X de N" bar while the sequential
	 * secure moves run off the request thread.
	 */
	public DuplicateDeletionResult delete(Collection<UUID> publicIds, DeletionProgressCallback progress) {
		String configured = appSettingService.stringValue(SettingsConstants.TRASH_FOLDER, "");

		if (configured == null || configured.isBlank()) {
			return new DuplicateDeletionResult(false, 0, 0, 0, 0, null,
					message("backend.duplicates.quarantineNotConfigured"));
		}

		if (publicIds == null || publicIds.isEmpty()) {
			return new DuplicateDeletionResult(true, 0, 0, 0, 0, null, message("backend.quarantine.noneSelected"));
		}

		Path quarantineRoot = Path.of(configured).toAbsolutePath().normalize();

		List<CatalogFile> files = catalogFileRepository.findByPublicIdIn(publicIds);

		Path[] lockedPaths = Stream
				.concat(Stream.of(quarantineRoot),
						files.stream().map(file -> PathUtils.normalizePath(file.getFileKey())))
				.distinct().toArray(Path[]::new);

		progress.update(0, publicIds.size());

		try (var _ = operationLockService.acquire(ExecutionType.DEDUP_DELETE, lockedPaths)) {
			return deleteLocked(publicIds, files, quarantineRoot, progress);
		} catch (OperationLockException lockError) {
			log.warn("Duplicate deletion blocked because another operation is using one of its paths: {}",
					lockError.getMessage());

			return new DuplicateDeletionResult(true, publicIds.size(), 0, 0, publicIds.size(), null,
					message("backend.duplicates.deletionLocked"));
		}
	}

	private DuplicateDeletionResult deleteLocked(Collection<UUID> publicIds, List<CatalogFile> files,
			Path quarantineRoot,
			DeletionProgressCallback progress) {
		Execution execution = startExecution(quarantineRoot);

		// Selected ids that map to no active catalog entry never reach the loop below;
		// count them as skipped up front so moved + skipped + errors always equals the
		// number the user requested (publicIds.size()), and the progress total matches.
		int total = publicIds.size();
		int unresolved = total - files.size();

		int moved = 0;
		int skipped = unresolved;
		int errors = 0;
		int processed = unresolved;

		if (unresolved > 0) {
			log.warn("Duplicate deletion skipped {} selected id(s) with no active catalog entry", unresolved);
		}

		List<UUID> movedIds = new ArrayList<>();

		for (CatalogFile file : files) {
			switch (quarantineOne(execution, file, quarantineRoot)) {
			case MOVED -> {
				moved++;
				movedIds.add(file.getPublicId());
			}
			case SKIPPED -> skipped++;
			case ERROR -> errors++;
			}

			progress.update(++processed, total);
		}

		// Keep the (cached) similar-photos AND similar-videos groups consistent without
		// a full recompute: drop the just-quarantined media from both caches so the
		// Duplicados screen reflects the deletion.
		similarityCaches.evictAll(movedIds);

		String message = message("backend.duplicates.deletionCompleted", moved, skipped, errors);

		finishExecution(execution, errors > 0 ? ExecutionStatus.FINISHED_WITH_ERRORS : ExecutionStatus.FINISHED, total,
				moved, skipped, errors, message);

		return new DuplicateDeletionResult(true, total, moved, skipped, errors,
				UuidV7.orLegacy(execution.getPublicId(), execution.getId()), message);
	}

	private Outcome quarantineOne(Execution execution, CatalogFile file, Path quarantineRoot) {
		if (!file.isActive()) {
			log.warn("Duplicate deletion skipped media file {} because its lifecycle is {}", file.getId(),
					file.getLifecycleStatus());

			return Outcome.SKIPPED;
		}

		Path source = PathUtils.normalizePath(file.getFileKey());

		if (source.startsWith(quarantineRoot)) {
			log.warn("Duplicate deletion skipped media file {} because it is already under quarantine: {}",
					file.getId(), source);

			return Outcome.SKIPPED;
		}

		if (!PhysicalFilePolicy.isProcessable(source)) {
			log.warn("Duplicate deletion skipped a non-physical entry: {}", source);

			return Outcome.SKIPPED;
		}

		if (!Files.exists(source)) {
			log.warn("Duplicate deletion skipped a missing file: {}", source);

			return Outcome.SKIPPED;
		}

		Path target = quarantineTarget(quarantineRoot, execution, file);

		try {
			// Same secure move as organization: SHA-256 baseline + byte-for-byte verify.
			secureFileMove.move(source, target, false);
		} catch (Exception e) {
			// An integrity failure leaves the file at target; put it back so nothing is
			// half-moved. If the move never happened (source still there) there is nothing
			// to
			// undo; if the roll-back itself fails, the file is orphaned and must be
			// flagged.
			boolean orphaned = !Files.exists(source) && Files.exists(target)
					&& !secureFileMove.rollback(target, source);

			if (orphaned) {
				log.error("Duplicate deletion could not securely move {} to quarantine and could not roll back from "
						+ "{}; the file is orphaned and needs manual recovery", source, target, e);
			} else {
				log.error("Duplicate deletion could not securely move {} to quarantine", source, e);
			}

			return Outcome.ERROR;
		}

		try {
			duplicateDeletionPersistence.persistQuarantine(execution, file, source, target);
		} catch (Exception e) {
			boolean rolledBack = secureFileMove.rollback(target, source);

			if (rolledBack) {
				log.error("Duplicate deletion moved {} but failed to update the catalog; rolled back", source, e);
			} else {
				log.error(
						"Duplicate deletion moved {} to {} but failed to update the catalog AND could not roll "
								+ "back; the file is orphaned in quarantine and needs manual recovery",
						source, target, e);
			}

			return Outcome.ERROR;
		}

		return Outcome.MOVED;
	}

	/**
	 * Collision-safe placement: duplicates frequently share a file name, so the
	 * quarantine copy is namespaced by execution and media-file id
	 * ({@code exec-<id>/<id>__<name>}). The {@code Movement} row keeps the exact
	 * original and quarantine paths, so undo is a plain move back regardless of
	 * this layout.
	 */
	private Path quarantineTarget(Path quarantineRoot, Execution execution, CatalogFile file) {
		return quarantineRoot.resolve("exec-" + execution.getId()).resolve(file.getId() + "__" + file.getFileName());
	}

	private Execution startExecution(Path quarantineRoot) {
		// sourcePath mirrors the target (the quarantine root) instead of null: the
		// shared undo
		// path feeds both through PathUtils.normalizePath for the operation lock.
		Execution execution = Execution.builder().executionType(ExecutionType.DEDUP_DELETE)
				.status(ExecutionStatus.STARTED).startedAt(LocalDateTime.now(clock))
				.sourcePath(PathUtils.normalize(quarantineRoot)).targetPath(PathUtils.normalize(quarantineRoot))
				.recursive(false).executeFlag(true)
				.statusMessage(StatusMessage.raw(message("backend.duplicates.deletionStarted"))).filesFound(0)
				.filesAnalyzed(0).cacheHits(0).filesMoved(0).simulatedFiles(0).errors(0).build();

		return executionRepository.save(execution);
	}

	private void finishExecution(Execution execution, ExecutionStatus status, long filesFound, long moved, long skipped,
			long errors, String message) {
		Execution managed = executionRepository.findById(execution.getId()).orElse(execution);

		managed.setStatus(status);
		managed.setFinishedAt(LocalDateTime.now(clock));
		managed.setFilesFound((int) filesFound);
		managed.setFilesAnalyzed((int) filesFound);
		managed.setFilesMoved((int) moved);
		managed.setCacheHits((int) skipped);
		managed.setErrors((int) errors);
		managed.setStatusMessage(StatusMessage.raw(message));

		executionRepository.save(managed);

		execution.setStatus(status);
		execution.setFinishedAt(managed.getFinishedAt());
	}
}