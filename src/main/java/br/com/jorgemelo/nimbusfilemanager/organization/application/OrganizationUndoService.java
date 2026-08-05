package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancelledException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OwnershipLostException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.organization.application.constants.OrganizationMessages;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.UndoResult;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.UndoStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.NumberUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OrganizationUndoService extends LocalizedComponent {


	private final ExecutionRepository executionRepository;
	private final CatalogFileRepository catalogFileRepository;
	private final CatalogFileLocationRepository catalogFileLocationRepository;
	private final OrganizationMovementLog organizationMovementLog;
	private final OperationLockService operationLockService;
	private final OrganizationPathValidator organizationPathValidator;
	private final ExecutionProgressService executionProgressService;
	private final ExecutionCancellationService executionCancellationService;
	private final LibraryFileMutations libraryFileMutations;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;

	public OrganizationUndoService(ExecutionRepository executionRepository, CatalogFileRepository catalogFileRepository,
			CatalogFileLocationRepository catalogFileLocationRepository,
			OrganizationMovementLog organizationMovementLog, OperationLockService operationLockService,
			OrganizationPathValidator organizationPathValidator, ExecutionProgressService executionProgressService,
			ExecutionCancellationService executionCancellationService, LibraryFileMutations libraryFileMutations,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.executionRepository = executionRepository;
		this.catalogFileRepository = catalogFileRepository;
		this.catalogFileLocationRepository = catalogFileLocationRepository;
		this.organizationMovementLog = organizationMovementLog;
		this.operationLockService = operationLockService;
		this.organizationPathValidator = organizationPathValidator;
		this.executionProgressService = executionProgressService;
		this.executionCancellationService = executionCancellationService;
		this.libraryFileMutations = libraryFileMutations;
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
			runUndo(undoExecution, movements, ownership);
		}
	}

	/**
	 * The counters live here, as locals, so that a run cut short - cancelled, or
	 * standing on locks it no longer holds - still reports exactly how much of it
	 * happened. Everything counted was moved back under the locks and verified byte
	 * for byte; what stops is the rest.
	 */
	private void runUndo(Execution undoExecution, List<Movement> movements, ExecutionOwnership ownership) {
		long undone = 0;
		long skipped = 0;
		long errors = 0;

		try {
			executionProgressService.updateTotal(undoExecution, movements.size());

			validateAllowed(movements);

			for (Movement movement : movements) {
				ensureNotCancelled(undoExecution);

				ownership.assertStillOwned();

				switch (undoOne(movement, undoExecution).status()) {
				case UNDONE -> undone++;
				case SKIPPED -> skipped++;
				case ERROR -> errors++;
				}

				reportProgress(undoExecution, undone, skipped, errors);
			}

			finishUndoExecution(undoExecution, errors > 0 ? ExecutionStatus.FINISHED_WITH_ERRORS
					: ExecutionStatus.FINISHED, undone, skipped, errors, OrganizationMessages::undoCompleted);
		} catch (ExecutionCancelledException _) {
			finishUndoExecution(undoExecution, ExecutionStatus.CANCELLED, undone, skipped, errors,
					OrganizationMessages::undoCancelled);
		} catch (OwnershipLostException ownershipLost) {
			log.warn("Undo {} stopped: {}", undoExecution.getId(), ownershipLost.getMessage());

			finishUndoExecution(undoExecution, ExecutionStatus.INTERRUPTED, undone, skipped, errors,
					OrganizationMessages::undoInterrupted);
		} catch (RuntimeException undoError) {
			log.error("Undo {} failed", undoExecution.getId(), undoError);

			failUndoExecution(undoExecution, undoError.getMessage());
		} finally {
			executionCancellationService.forget(undoExecution.getId());
		}
	}

	private List<Movement> undoableMovements(Execution execution) {
		return organizationMovementLog.undoable(execution.getId());
	}

	private Path[] lockedPaths(Execution execution, List<Movement> movements) {
		Stream<Path> executionPaths = Stream.of(PathUtils.normalizePath(execution.getSourcePath()),
				PathUtils.normalizePath(execution.getTargetPath()));

		Stream<Path> movementPaths = movements.stream()
				.flatMap(movement -> Stream.of(PathUtils.normalizePath(movement.getSourcePath()),
						PathUtils.normalizePath(movement.getTargetPath())));

		return Stream.concat(executionPaths, movementPaths).distinct().toArray(Path[]::new);
	}

	/**
	 * Every destination is checked before the first file moves. A movement row
	 * points wherever the file came from, and a run that discovered a forbidden
	 * path halfway would already have moved everything before it.
	 */
	private void validateAllowed(List<Movement> movements) {
		for (Movement movement : movements) {
			organizationPathValidator.validateAllowed(PathUtils.normalizePath(movement.getSourcePath()), "undo source");
			organizationPathValidator.validateAllowed(PathUtils.normalizePath(movement.getTargetPath()), "undo target");
		}
	}

	private void ensureNotCancelled(Execution undoExecution) {
		if (executionCancellationService.isCancelled(undoExecution.getId())) {
			throw new ExecutionCancelledException("Undo cancelled by user.");
		}
	}

	private void reportProgress(Execution undoExecution, long undone, long skipped, long errors) {
		executionProgressService.updateLiveProgress(undoExecution, NumberUtils.toInt(undone + skipped + errors),
				NumberUtils.toInt(undone), NumberUtils.toInt(skipped), NumberUtils.toInt(errors),
				ExecutionMessages.progressUpdated());
	}

	private UndoResult undoOne(Movement movement, Execution undoExecution) {
		if (movement.getStatus() == MovementStatus.UNDONE) {
			return new UndoResult(UndoStatus.SKIPPED, "Movement was already undone.");
		}

		Path source = PathUtils.normalizePath(movement.getSourcePath());
		Path target = PathUtils.normalizePath(movement.getTargetPath());

		if (!Files.exists(target)) {
			markUndoError(movement, undoExecution, MovementReason.SOURCE_NOT_FOUND, "Target file does not exist.");

			return new UndoResult(UndoStatus.ERROR, "Target file does not exist.");
		}

		if (Files.exists(source)) {
			markUndoError(movement, undoExecution, MovementReason.TARGET_EXISTS, "Original path already exists.");

			return new UndoResult(UndoStatus.ERROR, "Original path already exists.");
		}

		try {
			// Same secure move as the forward path: SHA-256 baseline + byte-for-byte
			// verify. Named for this execution so the watcher goes on recognising the
			// move as this product's own for as long as the undo holds its paths.
			libraryFileMutations.move(target, source, false, undoExecution.getId());

			// All catalog writes (location, media file and the movement row) commit
			// together
			// or not at all. Without this, a failure after the location save left the
			// catalog
			// pointing at the now-missing source while the file was rolled back to target.
			transactionTemplate.executeWithoutResult(_ -> applyUndoToDatabase(movement, undoExecution, source, target));

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

			markUndoError(movement, undoExecution, reason, e.getMessage());

			return new UndoResult(UndoStatus.ERROR, e.getMessage());
		}
	}

	private void applyUndoToDatabase(Movement movement, Execution undoExecution, Path source, Path target) {
		CatalogFile catalogFile = movement.getCatalogFile();

		if (catalogFile == null) {
			throw new IllegalStateException("Movement has no media file: " + movement.getId());
		}

		CatalogFileLocation location = catalogFileLocationRepository
				.findByCatalogFileIdAndCurrentPath(catalogFile.getId(), PathUtils.normalize(target))
				.orElseThrow(() -> new IllegalStateException("CatalogFileLocation not found for catalogFileId="
						+ catalogFile.getId() + " and path=" + target));
		Path parent = requireParent(source, "organization source");

		catalogFile.setFileKey(PathUtils.normalize(source));
		catalogFile.setFileName(source.getFileName().toString());
		catalogFile.setModifiedAt(readLastModifiedTime(source, catalogFile.getModifiedAt()));

		// Restores a quarantined duplicate to ACTIVE; a no-op for an already-active
		// organization file.
		catalogFile.markActive();

		location.setCurrentPath(PathUtils.normalize(source));
		location.setCurrentFolder(PathUtils.normalize(parent));
		location.setUpdatedAt(LocalDateTime.now(clock));

		catalogFileLocationRepository.save(location);
		catalogFileRepository.save(catalogFile);

		organizationMovementLog.recordUndone(movement, undoExecution);
	}

	/**
	 * Closes a row whose loop died on the way: an execution still holding a null
	 * {@code finishedAt} is read everywhere as the operation currently running.
	 */
	private void failUndoExecution(Execution undoExecution, String detail) {
		Execution managed = executionRepository.findById(undoExecution.getId()).orElse(undoExecution);

		managed.setStatus(ExecutionStatus.ERROR);
		managed.setFinishedAt(LocalDateTime.now(clock));
		executionProgressService.applyMessage(managed, OrganizationMessages.undoFailed(detail));

		executionRepository.save(managed);
	}

	private void finishUndoExecution(Execution undoExecution, ExecutionStatus status, long undone, long skipped,
			long errors, UndoOutcomeMessage messageKey) {
		Execution managed = executionRepository.findById(undoExecution.getId()).orElse(undoExecution);

		managed.setStatus(status);
		managed.setFinishedAt(LocalDateTime.now(clock));
		managed.setFilesAnalyzed(NumberUtils.toInt(undone + skipped + errors));
		managed.setFilesMoved(NumberUtils.toInt(undone));
		managed.setCacheHits(NumberUtils.toInt(skipped));
		managed.setErrors(NumberUtils.toInt(errors));
		executionProgressService.applyMessage(managed, messageKey.apply(undone, skipped, errors));

		executionRepository.save(managed);
	}

	private Path requireParent(Path path, String description) {
		Path parent = path.getParent();

		if (parent == null) {
			throw new IllegalStateException("A " + description + " path must have a parent directory: " + path);
		}

		return parent;
	}

	private LocalDateTime readLastModifiedTime(Path file, LocalDateTime fallback) {
		try {
			return LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault());
		} catch (IOException _) {
			return fallback;
		}
	}

	private void markUndoError(Movement movement, Execution undoExecution, MovementReason reason, String message) {
		organizationMovementLog.recordUndoFailure(movement, undoExecution, reason, message);
	}

}