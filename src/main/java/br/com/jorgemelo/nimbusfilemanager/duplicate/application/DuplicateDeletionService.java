package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateMessages;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeletionResult;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineIntakeService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
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
	private final QuarantineIntakeService quarantineIntakeService;
	private final OperationLockService operationLockService;
	private final ExecutionProgressService executionProgressService;
	private final ExecutionCancellationService executionCancellationService;
	private final ExecutionErrorService executionErrorService;
	private final EligibilityAnnouncer eligibilityAnnouncer;

	public DuplicateDeletionService(CatalogFileRepository catalogFileRepository,
			QuarantineIntakeService quarantineIntakeService, OperationLockService operationLockService,
			ExecutionProgressService executionProgressService,
			ExecutionCancellationService executionCancellationService, ExecutionErrorService executionErrorService,
			EligibilityAnnouncer eligibilityAnnouncer) {
		this.catalogFileRepository = catalogFileRepository;
		this.quarantineIntakeService = quarantineIntakeService;
		this.operationLockService = operationLockService;
		this.executionProgressService = executionProgressService;
		this.executionCancellationService = executionCancellationService;
		this.executionErrorService = executionErrorService;
		this.eligibilityAnnouncer = eligibilityAnnouncer;
	}

	/**
	 * Sends the named duplicates to quarantine, under the execution a worker
	 * claimed.
	 *
	 * @param ownership the claim on the paths, asked before each move. Everything
	 * already moved was moved under the locks and verified byte for byte; what must
	 * not happen is the next file leaving the library on the strength of a lock
	 * that is gone
	 */
	public DuplicateDeletionResult delete(Collection<UUID> publicIds, Execution execution,
			ExecutionOwnership ownership) {
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

		executionProgressService.updateTotal(ownership, publicIds.size());

		// The files are scattered across the library and no column could name them, so
		// the worker holds the quarantine root and these join the same session here.
		try (var _ = operationLockService.acquire(ExecutionType.DEDUP_DELETE, lockedPaths)) {
			return deleteLocked(publicIds, files, quarantineRoot, execution, ownership);
		} catch (OperationLockException lockError) {
			log.warn("Duplicate deletion blocked because another operation is using one of its paths: {}",
					lockError.getMessage());

			return new DuplicateDeletionResult(true, publicIds.size(), 0, 0, publicIds.size(), null,
					message("backend.duplicates.deletionLocked"));
		}
	}

	private DuplicateDeletionResult deleteLocked(Collection<UUID> publicIds, List<CatalogFile> files,
			Path quarantineRoot, Execution execution, ExecutionOwnership ownership) {
		try {
			return deleteEach(execution, publicIds, files, quarantineRoot, ownership);
		} catch (RuntimeException deletionError) {
			executionProgressService.fail(ownership, DuplicateMessages.failed(deletionError.getMessage()));

			throw deletionError;
		} finally {
			executionCancellationService.forget(execution.getId());
		}
	}

	private DuplicateDeletionResult deleteEach(Execution execution, Collection<UUID> publicIds, List<CatalogFile> files,
			Path quarantineRoot, ExecutionOwnership ownership) {
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

		for (CatalogFile file : files) {
			if (executionCancellationService.isCancelled(execution.getId())) {
				break;
			}

			ownership.assertMayGoOnWorking();

			switch (quarantineIntakeService.intake(execution, file, quarantineRoot,
					MovementReason.DUPLICATE_QUARANTINED, execution.getId())) {
			case MOVED -> moved++;
			case SKIPPED -> skipped++;
			case ERROR -> {
				errors++;

				// Counted and named: the execution screen used to report the failure without
				// ever saying which file it belonged to.
				executionErrorService.save(PathUtils.normalizePath(file.getFileKey()), ExecutionErrorType.MOVE_ERROR,
						message("backend.duplicates.moveFailed"), execution);
			}
			}

			processed++;

			// What has been dealt with, not what was asked for: the total belongs in
			// totalExpected, and putting it here made the bar full before the second
			// file was looked at.
			executionProgressService.updateLiveProgress(ownership, processed, processed, skipped, errors,
					ExecutionMessages.processing(file.getFileName()));
		}

		// Once for the batch, and only if a file really left: every file that moved
		// went from ACTIVE to DELETED, which is the first condition a duplicate
		// analysis reads. The relations stay - a quarantined file is one the answer no
		// longer includes, not one whose likeness to another was wrong.
		if (moved > 0) {
			eligibilityAnnouncer.announce("duplicate removal");
		}

		ExecutionMessage message = DuplicateMessages.deletionCompleted(moved, skipped, errors);

		executionProgressService.finishCommand(ownership,
				errors > 0 ? ExecutionStatus.FINISHED_WITH_ERRORS : ExecutionStatus.FINISHED,
				new ExecutionCounts(total, moved, skipped, errors), message);

		// The screen reads the report from the row, where the message is a code the
		// request localises. This one is only for whoever called in-process.
		return new DuplicateDeletionResult(true, total, moved, skipped, errors,
				UuidV7.orLegacy(execution.getPublicId(), execution.getId()),
				message("backend.duplicates.deletionCompleted", moved, skipped, errors));
	}

}