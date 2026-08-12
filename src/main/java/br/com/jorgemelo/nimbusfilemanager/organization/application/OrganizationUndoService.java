package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.CatalogTimestamp;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.ContentReconciliation;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancelledException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OwnershipLostException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.organization.application.constants.OrganizationMessages;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.UndoResult;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.UndoStatus;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.MovementRecovery;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.AppliedLocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.LocationChange;
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
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLocationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.NumberUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OrganizationUndoService extends LocalizedComponent {

	private final ExecutionRepository executionRepository;
	private final CatalogFileRepository catalogFileRepository;
	private final CatalogLocationWriter catalogLocationWriter;
	private final ContentReconciliation contentReconciliation;
	private final OrganizationMovementLog organizationMovementLog;
	private final OperationLockService operationLockService;
	private final OrganizationPathValidator organizationPathValidator;
	private final ExecutionProgressService executionProgressService;
	private final ExecutionCancellationService executionCancellationService;
	private final LibraryFileMutations libraryFileMutations;
	private final EligibilityAnnouncer eligibilityAnnouncer;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;

	public OrganizationUndoService(ExecutionRepository executionRepository, CatalogFileRepository catalogFileRepository,
			CatalogLocationWriter catalogLocationWriter, ContentReconciliation contentReconciliation,
			OrganizationMovementLog organizationMovementLog, OperationLockService operationLockService,
			OrganizationPathValidator organizationPathValidator, ExecutionProgressService executionProgressService,
			ExecutionCancellationService executionCancellationService, LibraryFileMutations libraryFileMutations,
			EligibilityAnnouncer eligibilityAnnouncer, PlatformTransactionManager transactionManager, Clock clock) {
		this.executionRepository = executionRepository;
		this.catalogFileRepository = catalogFileRepository;
		this.catalogLocationWriter = catalogLocationWriter;
		this.contentReconciliation = contentReconciliation;
		this.organizationMovementLog = organizationMovementLog;
		this.operationLockService = operationLockService;
		this.organizationPathValidator = organizationPathValidator;
		this.executionProgressService = executionProgressService;
		this.executionCancellationService = executionCancellationService;
		this.libraryFileMutations = libraryFileMutations;
		this.eligibilityAnnouncer = eligibilityAnnouncer;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/**
	 * Reverses what one execution moved, under the execution a worker claimed for
	 * the reversal.
	 *
	 * <p>
	 * Both organization moves and duplicate quarantine moves are plain
	 * source-to-target movements, so the same reversal undoes either; a
	 * DEDUP_DELETE additionally gets its files flipped back to ACTIVE in
	 * {@link #applyUndoToDatabase}.
	 *
	 * @param ownership the claim on the paths, asked before each move back. An undo
	 * that lost it stops where it stands: everything already reversed was reversed
	 * under the locks and verified byte for byte
	 */
	public void undo(long undoneExecutionId, Execution undoExecution, ExecutionOwnership ownership) {
		Execution undone = executionRepository.findById(undoneExecutionId)
				.orElseThrow(() -> new IllegalArgumentException("Execution not found: " + undoneExecutionId));

		List<Movement> movements = undoableMovements(undone);

		// Every path the undo will touch, not only the pair the worker already holds.
		// A DEDUP_DELETE locks the quarantine root, but each file goes back to its
		// ORIGINAL path, which lies outside it; without those, a concurrent
		// organization on the same tree would race the restore. Reentrant: the worker
		// already holds a session, and these keys join it.
		try (var _ = operationLockService.acquire(ExecutionType.UNDO, lockedPaths(undone, movements))) {
			runUndo(undoExecution, undone, movements, ownership);
		}
	}

	/**
	 * The counters live here, as locals, so that a run cut short - cancelled, or
	 * standing on locks it no longer holds - still reports exactly how much of it
	 * happened. Everything counted was moved back under the locks and verified byte
	 * for byte; what stops is the rest.
	 */
	private void runUndo(Execution undoExecution, Execution reversed, List<Movement> movements,
			ExecutionOwnership ownership) {
		long undone = 0;
		long skipped = 0;
		long errors = 0;

		try {
			executionProgressService.updateTotal(ownership, movements.size());

			validateAllowed(movements);

			Map<Long, PreparedMovement> reversals = organizationMovementLog.prepare(undoExecution,
					plannedReversals(movements), false);

			for (Movement movement : movements) {
				ensureNotCancelled(undoExecution);

				ownership.assertMayGoOnWorking();

				switch (undoOne(movement, undoExecution, reversals.get(catalogFileIdOf(movement))).status()) {
				case UNDONE -> undone++;
				case SKIPPED -> skipped++;
				case ERROR -> errors++;
				}

				reportProgress(ownership, undone, skipped, errors);
			}

			finishUndoExecution(ownership, errors > 0 ? ExecutionStatus.FINISHED_WITH_ERRORS
					: ExecutionStatus.FINISHED, undone, skipped, errors, OrganizationMessages::undoCompleted);
		} catch (ExecutionCancelledException _) {
			finishUndoExecution(ownership, ExecutionStatus.CANCELLED, undone, skipped, errors,
					OrganizationMessages::undoCancelled);
		} catch (OwnershipLostException ownershipLost) {
			log.warn("Undo {} stopped: {}", undoExecution.getId(), ownershipLost.getMessage());

			finishUndoExecution(ownership, ExecutionStatus.INTERRUPTED, undone, skipped, errors,
					OrganizationMessages::undoInterrupted);
		} catch (RuntimeException undoError) {
			log.error("Undo {} failed", undoExecution.getId(), undoError);

			executionProgressService.fail(ownership, OrganizationMessages.undoFailed(undoError.getMessage()));
		} finally {
			executionCancellationService.forget(undoExecution.getId());

			announceEligibility(reversed, movements, undone);
		}
	}

	/**
	 * One announcement for the reversal, in the {@code finally} because a run that
	 * stopped halfway still put back everything it put back.
	 *
	 * <p>
	 * The same reversal undoes two very different forward operations, and they
	 * change eligibility for different reasons. Reversing a quarantine flips files
	 * from DELETED back to ACTIVE, which puts them in the analysed set again
	 * whatever folder they land in - so the reasons on the rows being undone decide
	 * it outright. Reversing an organization move only sends files back where they
	 * came from, which matters only when one of the two ends is a folder the
	 * exclusion list has an opinion about.
	 *
	 * <p>
	 * What is deliberately not recomputed is the comparison: a file coming back is
	 * the case the stored relations were kept for, and what was already decided
	 * about how alike it is to another file is still true.
	 */
	private void announceEligibility(Execution reversed, List<Movement> movements, long undone) {
		if (undone == 0) {
			return;
		}

		// An organization movement carries no reason at all, and the set is
		// null-hostile - so the null is filtered rather than asked about.
		boolean restoresLifecycle = movements.stream().map(Movement::getReason).filter(Objects::nonNull)
				.anyMatch(QuarantineConstants.QUARANTINED_REASONS::contains);

		if (restoresLifecycle || eligibilityAnnouncer.repointCanChangeEligibility(reversed.getSourcePath(),
				reversed.getTargetPath())) {
			eligibilityAnnouncer.announce("organization undo");
		}
	}

	private List<Movement> undoableMovements(Execution execution) {
		return organizationMovementLog.undoable(execution.getId());
	}

	private Path[] lockedPaths(Execution execution, List<Movement> movements) {
		Stream<Path> executionPaths = Stream.of(PathUtils.normalizePath(execution.getSourcePath()),
				PathUtils.normalizePath(execution.getTargetPath()));

		Stream<Path> movementPaths = movements.stream()
				.flatMap(movement -> Stream.of(PathUtils.normalizePath(movement.getRequestedSourcePath()),
						PathUtils.normalizePath(movement.getRequestedTargetPath())));

		return Stream.concat(executionPaths, movementPaths).distinct().toArray(Path[]::new);
	}

	/**
	 * Every destination is checked before the first file moves. A movement row
	 * points wherever the file came from, and a run that discovered a forbidden
	 * path halfway would already have moved everything before it.
	 */
	private void validateAllowed(List<Movement> movements) {
		for (Movement movement : movements) {
			organizationPathValidator.validateAllowed(PathUtils.normalizePath(movement.getRequestedSourcePath()),
					"undo source");
			organizationPathValidator.validateAllowed(PathUtils.normalizePath(movement.getRequestedTargetPath()),
					"undo target");
		}
	}

	private void ensureNotCancelled(Execution undoExecution) {
		if (executionCancellationService.isCancelled(undoExecution.getId())) {
			throw new ExecutionCancelledException("Undo cancelled by user.");
		}
	}

	private void reportProgress(ExecutionOwnership ownership, long undone, long skipped, long errors) {
		executionProgressService.updateLiveProgress(ownership, NumberUtils.toInt(undone + skipped + errors),
				NumberUtils.toInt(undone), NumberUtils.toInt(skipped), NumberUtils.toInt(errors),
				ExecutionMessages.progressUpdated());
	}

	/**
	 * One reversing operation per file being put back. Each is a new operation with
	 * its own identity and its own reserved fact: undoing a move is a move, not an
	 * edit of the one being reversed.
	 */
	private List<MovementRequest> plannedReversals(List<Movement> movements) {
		return movements.stream().filter(movement -> movement.getCatalogFile() != null)
				.map(movement -> new MovementRequest(movement.getCatalogFile().getId(),
						PathUtils.normalizePath(movement.getRequestedTargetPath()),
						PathUtils.normalizePath(movement.getRequestedSourcePath()), MovementReason.UNDONE_BY_USER))
				.toList();
	}

	private Long catalogFileIdOf(Movement movement) {
		return movement.getCatalogFile() == null ? null : movement.getCatalogFile().getId();
	}

	private UndoResult undoOne(Movement movement, Execution undoExecution, PreparedMovement reversal) {
		if (movement.getStatus() == MovementStatus.UNDONE) {
			return new UndoResult(UndoStatus.SKIPPED, "Movement was already undone.");
		}

		Path source = PathUtils.normalizePath(movement.getRequestedSourcePath());
		Path target = PathUtils.normalizePath(movement.getRequestedTargetPath());

		// The reversal runs the other way round: out of the target and back to the
		// source. Read against its own record rather than against the disk alone,
		// because the disk cannot tell an undo that has not started from one whose
		// worker died between putting the file back and saying so - and the second,
		// judged by the file system, looks exactly like a file that vanished.
		switch (MovementRecovery.progressOf(reversal, target, source)) {
		case ALREADY_DONE -> {
			return new UndoResult(UndoStatus.SKIPPED, "Movement was already undone.");
		}
		case RESUME -> {
			return finishInterruptedUndo(movement, undoExecution, reversal, source, target);
		}
		case REFUSE -> {
			return refuseUndo(movement, undoExecution, reversal, target);
		}
		case EXECUTE -> {
			// The file is where the undo expects it; carry on below.
		}
		}

		if (Files.exists(source)) {
			markUndoError(undoExecution, reversal, target, MovementReason.TARGET_EXISTS,
					"Original path already exists.");

			return new UndoResult(UndoStatus.ERROR, "Original path already exists.");
		}

		try {
			// Same secure move as the forward path: SHA-256 baseline + byte-for-byte
			// verify. Named for this execution so the watcher goes on recognising the
			// move as this product's own for as long as the undo holds its paths.
			MoveBaseline moved = libraryFileMutations.move(target, source, false, undoExecution.getId());

			// All catalog writes (location, media file and the movement row) commit
			// together
			// or not at all. Without this, a failure after the location save left the
			// catalog
			// pointing at the now-missing source while the file was rolled back to target.
			transactionTemplate.executeWithoutResult(_ -> {
				applyUndoToDatabase(movement, reversal, undoExecution, source, target);

				// The digest the undo's own secure move already proved, in the same
				// transaction as the catalog write. Nothing is read again.
				contentReconciliation.reconcileFromDigest(movement.getCatalogFile(), moved.sha256(),
						moved.sizeBytes(), CatalogEventSources.ORGANIZATION, Instant.now(clock));
			});

			return new UndoResult(UndoStatus.UNDONE, "Movement undone.");
		} catch (Exception e) {
			// If the file made it back to the original path but a later step failed, put it
			// back
			// at the target so disk and catalog stay consistent (same policy as the
			// executor).
			if (!Files.exists(target) && Files.exists(source)) {
				libraryFileMutations.rollback(source, target);
			}

			MovementReason reason = e instanceof MoveIntegrityException ? MovementReason.INTEGRITY_CHECK_FAILED
					: MovementReason.IO_ERROR;

			log.error("Could not undo movement. executionId={} movementId={} source={} target={}",
					movement.getExecution().getId(), movement.getId(), source, target, e);

			markUndoError(undoExecution, reversal, target, reason, e.getMessage());

			return new UndoResult(UndoStatus.ERROR, e.getMessage());
		}
	}

	/**
	 * The file is already back and nobody wrote it down.
	 *
	 * <p>
	 * Finished rather than repeated: moving it again would be moving whatever is
	 * at the target now, and there is nothing there. The fact is recorded under
	 * the identity this reversal reserved before any of it happened, so the
	 * history reads as one undo that took two attempts rather than as two undos.
	 *
	 * <p>
	 * With no digest, deliberately: this attempt moved nothing, so it proved
	 * nothing about the bytes, and offering the previous attempt's proof would be
	 * claiming a reading nobody took.
	 */
	private UndoResult finishInterruptedUndo(Movement movement, Execution undoExecution, PreparedMovement reversal,
			Path source, Path target) {
		log.warn("Resuming an undo whose file had already gone back. movementId={} source={} target={}",
				movement.getId(), source, target);

		transactionTemplate
				.executeWithoutResult(_ -> applyUndoToDatabase(movement, reversal, undoExecution, source, target));

		return new UndoResult(UndoStatus.UNDONE, "Movement undone.");
	}

	/**
	 * Neither end of the reversal is on disk, or the operation was settled as
	 * something this is not entitled to overrule. Reported rather than guessed at.
	 */
	private UndoResult refuseUndo(Movement movement, Execution undoExecution, PreparedMovement reversal, Path target) {
		String message = Files.exists(target) ? "The reversal is not an operation this undo may carry out."
				: "Target file does not exist.";

		log.warn("Refusing to undo movement {}: {}", movement.getId(), message);

		markUndoError(undoExecution, reversal, target, MovementReason.SOURCE_NOT_FOUND, message);

		return new UndoResult(UndoStatus.ERROR, message);
	}

	private void applyUndoToDatabase(Movement movement, PreparedMovement reversal, Execution undoExecution,
			Path source, Path target) {
		CatalogFile catalogFile = movement.getCatalogFile();

		if (catalogFile == null) {
			throw new IllegalStateException("Movement has no media file: " + movement.getId());
		}

		// The reversal is a location change like any other, recorded under the identity
		// its own operation reserved - the fact the original produced is untouched and
		// stays true.
		AppliedLocationChange applied = catalogLocationWriter.relocate(new LocationChange(catalogFile.getId(),
				reversal.catalogFileEventPublicId(), target, source,
				new CatalogFactProvenance(Instant.now(clock), CatalogEventSources.ORGANIZATION,
						CatalogEventEvidence.NIMBUS_OPERATION, null)));

		// The entry still names where the file was before the reversal, and the save
		// below is a merge that cascades to the placement.
		catalogFile.getLocation().placedAt(applied.currentPath(), applied.pathKey(), applied.currentFolder());

		catalogFile.setModifiedAt(readLastModifiedTime(source, catalogFile.getModifiedAt()));

		// Restores a quarantined duplicate to ACTIVE; a no-op for an already-active
		// organization file.
		catalogFile.markActive();

		catalogFileRepository.save(catalogFile);

		organizationMovementLog.recordUndone(undoExecution, reversal, movement);
	}

	/**
	 * Closes the row with what the reversal got through: the movements it reached
	 * are the items, and how each of them ended is what the three counters say.
	 */
	private void finishUndoExecution(ExecutionOwnership ownership, ExecutionStatus status, long undone, long skipped,
			long errors, UndoOutcomeMessage messageKey) {
		ExecutionCounts counts = new ExecutionCounts(NumberUtils.toInt(undone + skipped + errors),
				NumberUtils.toInt(undone), NumberUtils.toInt(skipped), NumberUtils.toInt(errors));

		executionProgressService.finishCommand(ownership, status, counts, messageKey.apply(undone, skipped, errors));
	}

	private Instant readLastModifiedTime(Path file, Instant fallback) {
		try {
			return CatalogTimestamp.observed(Files.getLastModifiedTime(file));
		} catch (IOException _) {
			return fallback;
		}
	}

	/**
	 * The reversing operation failed. The one it was reversing is left as it was:
	 * it did move the file, which is still true, and an undo that could not run is
	 * the undo's failure and not a correction of history.
	 */
	private void markUndoError(Execution undoExecution, PreparedMovement reversal, Path source, MovementReason reason,
			String message) {
		organizationMovementLog.recordUndoFailure(undoExecution, reversal, source, reason, message);
	}
}