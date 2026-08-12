package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionStopReason;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineMessages;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreBatchResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.RestoreMove;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.RestoreOutcome;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.MovementRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.MovementWriter;
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
	private final MovementWriter movementWriter;
	private final FileHashService fileHashService;
	private final QuarantinePersistence quarantinePersistence;
	private final LibraryFileMutations libraryFileMutations;
	private final OperationLockService operationLockService;
	private final ExecutionStopReason executionStopReason;
	private final QuarantineOperationLog restoreLog;
	private final EligibilityAnnouncer eligibilityAnnouncer;

	QuarantineService(MovementRepository movementRepository, MovementWriter movementWriter,
			FileHashService fileHashService, QuarantinePersistence quarantinePersistence,
			LibraryFileMutations libraryFileMutations, OperationLockService operationLockService,
			ExecutionStopReason executionStopReason, QuarantineOperationLog restoreLog,
			EligibilityAnnouncer eligibilityAnnouncer) {
		this.movementRepository = movementRepository;
		this.movementWriter = movementWriter;
		this.fileHashService = fileHashService;
		this.quarantinePersistence = quarantinePersistence;
		this.libraryFileMutations = libraryFileMutations;
		this.operationLockService = operationLockService;
		this.executionStopReason = executionStopReason;
		this.restoreLog = restoreLog;
		this.eligibilityAnnouncer = eligibilityAnnouncer;
	}

	/**
	 * Finishing a restore whose file already left quarantine, which is what a
	 * worker dying between the file system and the database leaves behind.
	 *
	 * <p>
	 * The destination is a folder of the user's library, so its mere existence
	 * proves nothing - unlike a quarantine path, which this product namespaces by
	 * execution and file. What proves it here is the content: the catalogued
	 * SHA-256 against the file now sitting at the destination.
	 *
	 * <p>
	 * Which means the recovery is only available when the catalog has a hash, and
	 * that is not always. A duplicate always does - nothing becomes a duplicate
	 * candidate without one. A file quarantined from the Files screen, or the
	 * original of a conversion, may not: hashing is opt-in on inventory and is
	 * deliberately skipped for archives wearing a media extension. Without a hash
	 * there is no safe evidence, so nothing is adopted and the operation stays
	 * pending for somebody to look at - which is the honest outcome, not a
	 * limitation to work around by falling back on size.
	 */
	private QuarantineRestoreResult resumeInterrupted(Execution execution, Movement movement, UUID movementId,
			PreparedMovement operation) {
		if (operation == null || operation.status() != MovementStatus.PENDING) {
			return null;
		}

		Path destination = PathUtils.normalizePath(operation.requestedTargetPath());

		if (!Files.exists(destination) || !sameContent(movement.getCatalogFile(), destination)) {
			return null;
		}

		log.warn("Resuming a restore whose file had already been moved. movement={} destination={}",
				operation.movementPublicId(), destination);

		// Resumed after a crash: this attempt moved nothing, so it proved nothing
		// about the bytes and has no digest to offer.
		QuarantineRestoreResult catalogError = restoreCatalog(execution, movement, movementId,
				PathUtils.normalizePath(operation.requestedSourcePath()), destination, operation, null);

		return catalogError != null ? catalogError
				: result(movementId, RestoreOutcome.RESTORED, message("backend.quarantine.restored"),
						PathUtils.normalize(destination));
	}

	private boolean sameContent(CatalogFile catalogFile, Path destination) {
		if (catalogFile == null || catalogFile.getSha256() == null || catalogFile.getSha256().isBlank()) {
			return false;
		}

		try {
			return catalogFile.getSha256().equalsIgnoreCase(fileHashService.sha256(destination));
		} catch (RuntimeException e) {
			log.warn("Could not read {} to decide whether a restore had already happened", destination, e);

			return false;
		}
	}

	/**
	 * The operations for everything in the batch that has somewhere to go.
	 *
	 * <p>
	 * Items whose destination cannot be decided - a name already taken, an origin
	 * folder that no longer exists - get no operation, and that is right: nothing
	 * will be attempted for them, and an operation records an attempt.
	 */
	private Map<UUID, PreparedMovement> prepareRestores(Execution execution, List<UUID> movementIds, Path decided) {
		Map<Long, UUID> movementByFile = new LinkedHashMap<>();

		List<MovementRequest> requests = new ArrayList<>();

		for (UUID movementId : movementIds) {
			Movement quarantine = movementRepository.findByMovementPublicId(movementId).orElse(null);

			if (quarantine == null || !isQuarantined(quarantine) || quarantine.getCatalogFile() == null) {
				continue;
			}

			Path source = PathUtils.normalizePath(quarantine.getRequestedTargetPath());
			Path target = destinationOf(quarantine, decided);

			if (target == null) {
				continue;
			}

			movementByFile.put(quarantine.getCatalogFile().getId(), movementId);

			requests.add(new MovementRequest(quarantine.getCatalogFile().getId(), source, target,
					MovementReason.RESTORED_FROM_QUARANTINE));
		}

		Map<UUID, PreparedMovement> operations = new LinkedHashMap<>();

		movementWriter.prepare(execution.getId(), requests)
				.forEach(prepared -> operations.put(movementByFile.get(prepared.catalogFileId()), prepared));

		return operations;
	}

	/**
	 * Where the file would go, or null when that cannot be decided yet. Read-only:
	 * the answers that keep a file in quarantine are given before anything is
	 * attempted, so they never become operations.
	 */
	private Path destinationOf(Movement quarantine, Path decided) {
		if (decided != null) {
			return decided;
		}

		Path original = PathUtils.normalizePath(quarantine.getRequestedSourcePath());
		Path originFolder = original.getParent();

		if (originFolder == null || !Files.isDirectory(originFolder)) {
			return null;
		}

		return originFolder.resolve(original.getFileName());
	}

	private boolean isQuarantined(Movement movement) {
		return movement.getStatus() == MovementStatus.MOVED
				&& QuarantineConstants.QUARANTINED_REASONS.contains(movement.getReason());
	}

	private QuarantineRestoreResult restoreOne(Execution execution, UUID movementId, Path decided,
			Map<UUID, PreparedMovement> operations) {
		Movement movement = movementRepository.findByMovementPublicId(movementId).orElse(null);

		if (movement == null) {
			return result(movementId, RestoreOutcome.ERROR, message("backend.quarantine.itemNotFound"), null);
		}

		// Quarantine is not a lifecycle: a file may be DELETED for other reasons, so
		// what proves it is in quarantine is the operation that put it there.
		if (!isQuarantined(movement) || movement.getCatalogFile() == null || !movement.getCatalogFile().isDeleted()) {
			return result(movementId, RestoreOutcome.ERROR, message("backend.quarantine.notQuarantined"), null);
		}

		Path quarantine = PathUtils.normalizePath(movement.getRequestedTargetPath());

		if (!Files.exists(quarantine)) {
			QuarantineRestoreResult resumed = resumeInterrupted(execution, movement, movementId,
					operations.get(movementId));

			if (resumed != null) {
				return resumed;
			}

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

		return restoreTo(execution, movement, movementId, quarantine, decided, operations.get(movementId));
	}

	/**
	 * Where the file goes: the destination somebody decided, or - for a batch,
	 * which nobody was asked about - its own origin, refusing anything that would
	 * need a decision.
	 */
	private QuarantineRestoreResult restoreTo(Execution execution, Movement movement, UUID movementId, Path quarantine,
			Path decided, PreparedMovement operation) {
		Path original = PathUtils.normalizePath(movement.getRequestedSourcePath());

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
			return result(movementId, RestoreOutcome.CONFLICT, message("backend.quarantine.destinationConflict"), null);
		}

		return moveBack(execution, movement, quarantine, destination, operation);
	}

	/**
	 * Restores the given selection at once, under the execution a worker claimed. A
	 * batch puts every item back at its own origin; the single restore arrives with
	 * the destination its conversation settled on, and both run through the same
	 * loop.
	 */
	QuarantineRestoreBatchResult restoreMany(List<UUID> movementIds, Path destination, Execution execution,
			ExecutionOwnership ownership) {
		QuarantineRestoreBatchResult result = restoreAll(movementIds == null ? List.of() : movementIds, destination,
				execution, ownership);

		// Once per batch and only if something really came back: a restore puts files
		// at their origin and marks them active again, which is exactly the pair of
		// columns a duplicate analysis decides eligibility by. A batch that restored
		// nothing changed nothing, and announcing it would queue work for no reason.
		if (result.restored() > 0) {
			eligibilityAnnouncer.announce("quarantine restore");
		}

		return result;
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
			restoreLog.fail(ownership, restoreError.getMessage());

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

		// Every restore that can be attempted is on record before the first file leaves
		// quarantine. Resolving them first costs one read per item and buys a single
		// prepare for the batch - and, more to the point, an anchor a later attempt can
		// find.
		Map<UUID, PreparedMovement> operations = prepareRestores(execution, movementIds, destination);

		for (UUID movementId : movementIds) {
			stoppedAs = executionStopReason.of(execution, ownership);

			if (stoppedAs != null) {
				break;
			}

			QuarantineRestoreResult item = restoreOne(execution, movementId, destination, operations);

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
			restoreLog.finish(ownership, movementIds.size(), restored, unrestored, errors, outcome);
		} else {
			restoreLog.stop(ownership, stoppedAs, movementIds.size(), restored, unrestored, errors, outcome);
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

	private QuarantineRestoreResult moveBack(Execution execution, Movement movement, Path quarantine, Path destination,
			PreparedMovement operation) {
		UUID movementId = movement.getMovementPublicId();

		if (operation == null || operation.status() != MovementStatus.PENDING) {
			return result(movementId, RestoreOutcome.ERROR, message("backend.quarantine.notQuarantined"), null);
		}

		try (var _ = operationLockService.acquire(ExecutionType.QUARANTINE_RESTORE, quarantine, destination)) {
			RestoreMove moved = restoreSecureMove(execution, movementId, quarantine, destination);

			QuarantineRestoreResult moveError = moved.failure();

			if (moveError != null) {
				return moveError;
			}

			QuarantineRestoreResult catalogError = restoreCatalog(execution, movement, movementId, quarantine,
					destination, operation, moved.baseline());

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

	private RestoreMove restoreSecureMove(Execution execution, UUID movementId, Path quarantine, Path destination) {
		try {
			// Same secure move as everywhere else: SHA-256 baseline + byte-for-byte
			// verify. Its digest is kept rather than discarded: it is a proof about the
			// current bytes that the verification has already paid for.
			return new RestoreMove(null, libraryFileMutations.move(quarantine, destination, false, execution.getId()));
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

			return new RestoreMove(result(movementId, RestoreOutcome.ERROR,
					message("backend.quarantine.moveFailed", moveError.getMessage()), null), null);
		}
	}

	/**
	 * The catalog side, and what a refusal means.
	 *
	 * <p>
	 * A rolled-back file means nothing happened, so the operation failed and says
	 * so. A file left at the destination with the catalog still pointing at
	 * quarantine is the case that must not be called a failure: the physical work
	 * is done, and marking the operation failed would throw away the only anchor a
	 * later attempt has to finish it.
	 */
	private QuarantineRestoreResult restoreCatalog(Execution execution, Movement movement, UUID movementId,
			Path quarantine, Path destination, PreparedMovement operation, MoveBaseline baseline) {
		try {
			quarantinePersistence.persistRestore(execution.getId(), operation, movement.getCatalogFile(), quarantine,
					destination, baseline);

			return null;
		} catch (Exception catalogError) {
			if (libraryFileMutations.rollback(destination, quarantine)) {
				log.error("Quarantine restore moved {} but failed to update the catalog; rolled back", destination,
						catalogError);

				movementWriter.markFailed(execution.getId(), List.of(operation.movementPublicId()),
						MovementReason.DATABASE_UPDATE_FAILED);
			} else {
				log.error(
						"Quarantine restore moved {} to {} but failed to update the catalog AND could not roll "
								+ "back; the operation stays pending so a later attempt can finish it",
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