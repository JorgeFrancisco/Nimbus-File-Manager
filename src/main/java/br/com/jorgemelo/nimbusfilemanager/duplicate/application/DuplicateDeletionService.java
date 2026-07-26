package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeletionResult;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineIntakeService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
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
	private final QuarantineIntakeService quarantineIntakeService;
	private final SimilarityCaches similarityCaches;
	private final OperationLockService operationLockService;
	private final Clock clock;

	public DuplicateDeletionService(CatalogFileRepository catalogFileRepository,
			ExecutionRepository executionRepository, QuarantineIntakeService quarantineIntakeService,
			SimilarityCaches similarityCaches, OperationLockService operationLockService, Clock clock) {
		this.catalogFileRepository = catalogFileRepository;
		this.executionRepository = executionRepository;
		this.quarantineIntakeService = quarantineIntakeService;
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
		Optional<Path> configured = quarantineIntakeService.root();

		if (configured.isEmpty()) {
			return new DuplicateDeletionResult(false, 0, 0, 0, 0, null,
					message("backend.duplicates.quarantineNotConfigured"));
		}

		if (publicIds == null || publicIds.isEmpty()) {
			return new DuplicateDeletionResult(true, 0, 0, 0, 0, null, message("backend.quarantine.noneSelected"));
		}

		Path quarantineRoot = configured.get();

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
			switch (quarantineIntakeService.intake(execution, file, quarantineRoot,
					MovementReason.DUPLICATE_QUARANTINED)) {
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