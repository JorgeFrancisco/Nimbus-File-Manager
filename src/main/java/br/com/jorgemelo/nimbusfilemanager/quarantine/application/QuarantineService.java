package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateDeletionPersistence;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionStopReason;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineMessages;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreBatchResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.RestoreOutcome;
import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PhysicalFilePolicy;
import lombok.extern.slf4j.Slf4j;

/**
 * Read and restore side of the duplicate quarantine, backing the Quarentena
 * screen. The listing is driven by the {@code Movement} audit rows that
 * recorded each soft-delete (they hold the exact original and quarantine
 * paths), so every item can be moved straight back with the same verified move
 * primitive used everywhere else. Nothing is ever overwritten.
 *
 * <p>
 * The restoring half runs in the worker, off the queue - one file or a whole
 * selection, through the same loop. What is <em>not</em> here is the
 * conversation: whether an alternate folder is needed and what to do about a
 * name collision are questions for the person, answered by
 * {@link QuarantineRestorePlanner} before anything is queued. By the time this
 * runs there is nothing left to ask, only what to do if the world moved in the
 * meantime.
 */
@Slf4j
@Service
class QuarantineService extends LocalizedComponent {

	private final MovementRepository movementRepository;
	private final DuplicateDeletionPersistence duplicateDeletionPersistence;
	private final LibraryFileMutations libraryFileMutations;
	private final OperationLockService operationLockService;
	private final ExecutionStopReason executionStopReason;
	private final QuarantineOperationLog restoreLog;

	QuarantineService(MovementRepository movementRepository,
			DuplicateDeletionPersistence duplicateDeletionPersistence, LibraryFileMutations libraryFileMutations,
			OperationLockService operationLockService, ExecutionStopReason executionStopReason,
			QuarantineOperationLog restoreLog) {
		this.movementRepository = movementRepository;
		this.duplicateDeletionPersistence = duplicateDeletionPersistence;
		this.libraryFileMutations = libraryFileMutations;
		this.operationLockService = operationLockService;
		this.executionStopReason = executionStopReason;
		this.restoreLog = restoreLog;
	}

	private QuarantineRestoreResult restoreOne(Execution execution, UUID movementId, Path decided) {
		Movement movement = movementRepository.findByPublicId(movementId).orElse(null);

		if (movement == null) {
			return result(movementId, RestoreOutcome.ERROR, message("backend.quarantine.itemNotFound"), null);
		}

		if (movement.getStatus() != MovementStatus.MOVED
				|| !QuarantineConstants.QUARANTINED_REASONS.contains(movement.getReason())) {
			return result(movementId, RestoreOutcome.ERROR, message("backend.quarantine.notQuarantined"), null);
		}

		Path quarantine = PathUtils.normalizePath(movement.getTargetPath());

		if (!Files.exists(quarantine)) {
			restoreLog.recordFailure(execution, quarantine, ExecutionErrorType.FILE_NOT_FOUND,
					message("backend.quarantine.fileMissing"));

			return result(movementId, RestoreOutcome.MISSING_IN_QUARANTINE, message("backend.quarantine.fileMissing"),
					null);
		}

		if (!PhysicalFilePolicy.isProcessable(quarantine)) {
			// Same rule as the forward path (DuplicateDeletionService): never follow a
			// symlink/junction/.lnk. If the quarantine copy was swapped for a link, refuse
			// instead of "restoring" the link into the library.
			restoreLog.recordFailure(execution, quarantine, ExecutionErrorType.UNKNOWN,
					message("backend.quarantine.notPhysical"));

			return result(movementId, RestoreOutcome.ERROR, message("backend.quarantine.notPhysical"), null);
		}

		return restoreTo(execution, movement, movementId, quarantine, decided);
	}

	/**
	 * Where the file goes: the destination somebody decided, or - for a batch,
	 * which nobody was asked about - its own origin, refusing anything that would
	 * need a decision.
	 */
	private QuarantineRestoreResult restoreTo(Execution execution, Movement movement, UUID movementId, Path quarantine,
			Path decided) {
		Path original = PathUtils.normalizePath(movement.getSourcePath());

		Path destination = decided;

		if (destination == null) {
			Path originFolder = original.getParent();

			if (originFolder == null) {
				return result(movementId, RestoreOutcome.ERROR, message("backend.quarantine.invalidOriginalPath"),
						null);
			}

			if (!Files.isDirectory(originFolder)) {
				return result(movementId, RestoreOutcome.ORIGIN_MISSING, message("backend.quarantine.originMissing"),
						null);
			}

			destination = originFolder.resolve(original.getFileName());
		}

		if (Files.exists(destination)) {
			return result(movementId, RestoreOutcome.CONFLICT, message("backend.quarantine.destinationConflict"),
					null);
		}

		return moveBack(execution, movement, quarantine, destination);
	}

	/**
	 * Restores the given selection at once, under the execution a worker claimed.
	 * A batch puts every item back at its own origin; the single restore arrives
	 * with the destination its conversation settled on, and both run through the
	 * same loop.
	 */
	QuarantineRestoreBatchResult restoreMany(List<UUID> movementIds, Path destination, Execution execution,
			ExecutionOwnership ownership) {
		return restoreAll(movementIds == null ? List.of() : movementIds, destination, execution, ownership);
	}

	/**
	 * The one restore loop, shared by the single button and the batch: both are a
	 * user action that moves files, so both open one execution and close it with
	 * what happened. Items that only need a decision (a name collision, a missing
	 * origin folder) are counted apart from failures - they stay in quarantine and
	 * are not errors.
	 */
	private QuarantineRestoreBatchResult restoreAll(List<UUID> movementIds, Path destination, Execution execution,
			ExecutionOwnership ownership) {
		try {
			return restoreEach(execution, movementIds, destination, ownership);
		} catch (RuntimeException restoreError) {
			restoreLog.fail(execution, restoreError.getMessage());

			throw restoreError;
		}
	}

	private QuarantineRestoreBatchResult restoreEach(Execution execution, List<UUID> movementIds, Path destination,
			ExecutionOwnership ownership) {
		List<QuarantineRestoreResult> items = new ArrayList<>();

		int restored = 0;
		int conflicts = 0;
		int originMissing = 0;
		int errors = 0;

		ExecutionStatus stoppedAs = null;

		for (UUID movementId : movementIds) {
			stoppedAs = executionStopReason.of(execution, ownership);

			if (stoppedAs != null) {
				break;
			}

			QuarantineRestoreResult item = restoreOne(execution, movementId, destination);

			items.add(item);

			// No SKIPPED here: keeping a file in quarantine is an answer somebody gave,
			// and it is given before anything is queued - the planner ends the request
			// with it and nothing reaches this loop.
			switch (RestoreOutcome.valueOf(item.outcome())) {
			case RESTORED -> restored++;
			case CONFLICT -> conflicts++;
			case ORIGIN_MISSING -> originMissing++;
			default -> errors++;
			}
		}

		ExecutionMessage outcome = outcomeOf(stoppedAs, restored, conflicts, originMissing, errors);

		int unrestored = conflicts + originMissing;

		if (stoppedAs == null) {
			restoreLog.finish(execution, movementIds.size(), restored, unrestored, errors, outcome);
		} else {
			restoreLog.stop(execution, stoppedAs, movementIds.size(), restored, unrestored, errors, outcome);
		}

		return new QuarantineRestoreBatchResult(errors == 0 && stoppedAs == null, movementIds.size(), restored,
				conflicts, originMissing, errors, resolve(outcome), items);
	}

	private ExecutionMessage outcomeOf(ExecutionStatus stoppedAs, int restored, int conflicts, int originMissing,
			int errors) {
		if (stoppedAs == ExecutionStatus.CANCELLED) {
			return QuarantineMessages.batchCancelled(restored, conflicts, originMissing, errors);
		}

		if (stoppedAs == ExecutionStatus.INTERRUPTED) {
			return QuarantineMessages.batchInterrupted(restored, conflicts, originMissing, errors);
		}

		return QuarantineMessages.batchCompleted(restored, conflicts, originMissing, errors);
	}

	/**
	 * The row keeps the message as a code, which whoever reads it localises. This
	 * one is for the caller in this process, which has a language already.
	 */
	private String resolve(ExecutionMessage outcome) {
		return message(outcome.code(), outcome.args().toArray());
	}

	private QuarantineRestoreResult moveBack(Execution execution, Movement movement, Path quarantine,
			Path destination) {
		UUID movementId = movement.getPublicId();

		try (var _ = operationLockService.acquire(ExecutionType.QUARANTINE_RESTORE, quarantine, destination)) {
			QuarantineRestoreResult moveError = restoreSecureMove(execution, movementId, quarantine, destination);

			if (moveError != null) {
				return moveError;
			}

			QuarantineRestoreResult catalogError = restoreCatalog(execution, movement, movementId, quarantine,
					destination);

			if (catalogError != null) {
				return catalogError;
			}

			return result(movementId, RestoreOutcome.RESTORED, message("backend.quarantine.restored"),
					PathUtils.normalize(destination));
		} catch (OperationLockException _) {
			restoreLog.recordFailure(execution, quarantine, ExecutionErrorType.ACCESS_DENIED,
					message("backend.quarantine.pathLocked"));

			return result(movementId, RestoreOutcome.LOCKED, message("backend.quarantine.pathLocked"), null);
		}
	}

	private QuarantineRestoreResult restoreSecureMove(Execution execution, UUID movementId, Path quarantine,
			Path destination) {
		try {
			// Same secure move as everywhere else: SHA-256 baseline + byte-for-byte verify.
			libraryFileMutations.move(quarantine, destination, false, execution.getId());

			return null;
		} catch (Exception moveError) {
			// A verify failure leaves the file at the destination; put it back so nothing
			// is half-restored. If the roll-back itself fails, the file is orphaned.
			boolean orphaned = !Files.exists(quarantine) && Files.exists(destination)
					&& !libraryFileMutations.rollback(destination, quarantine);

			if (orphaned) {
				log.error(
						"Quarantine restore could not move {} back to {} and could not roll back; the file is "
								+ "orphaned at the destination and needs manual recovery",
						quarantine, destination, moveError);
			} else {
				log.error("Quarantine restore could not move {} back to {}", quarantine, destination, moveError);
			}

			restoreLog.recordFailure(execution, quarantine, ExecutionErrorType.MOVE_ERROR,
					message("backend.quarantine.moveFailed", moveError.getMessage()));

			return result(movementId, RestoreOutcome.ERROR,
					message("backend.quarantine.moveFailed", moveError.getMessage()), null);
		}
	}

	private QuarantineRestoreResult restoreCatalog(Execution execution, Movement movement, UUID movementId,
			Path quarantine, Path destination) {
		try {
			duplicateDeletionPersistence.applyRestore(movement, destination, execution);

			return null;
		} catch (Exception catalogError) {
			boolean rolledBack = libraryFileMutations.rollback(destination, quarantine);

			if (rolledBack) {
				log.error("Quarantine restore moved {} but failed to update the catalog; rolled back", destination,
						catalogError);
			} else {
				log.error(
						"Quarantine restore moved {} to {} but failed to update the catalog AND could not roll "
								+ "back; the restored file is now outside the catalog and needs manual recovery",
						quarantine, destination, catalogError);
			}

			restoreLog.recordFailure(execution, quarantine, catalogError);

			return result(movementId, RestoreOutcome.ERROR, message("backend.quarantine.catalogFailed"), null);
		}
	}

	private QuarantineRestoreResult result(UUID movementId, RestoreOutcome outcome, String message,
			String restoredPath) {
		return new QuarantineRestoreResult(outcome == RestoreOutcome.RESTORED, outcome.name(), message, movementId,
				restoredPath);
	}
}