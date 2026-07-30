package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationUndoItemResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationUndoResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.UndoResult;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.UndoStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;
import lombok.extern.slf4j.Slf4j;

@Slf4j @Service
public class OrganizationUndoService extends LocalizedComponent {

	private final ExecutionRepository executionRepository;
	private final CatalogFileRepository catalogFileRepository;
	private final CatalogFileLocationRepository catalogFileLocationRepository;
	private final OrganizationMovementLog organizationMovementLog;
	private final OperationLockService operationLockService;
	private final OrganizationPathValidator organizationPathValidator;
	private final SecureFileMove secureFileMove;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;

	public OrganizationUndoService(ExecutionRepository executionRepository, CatalogFileRepository catalogFileRepository,
			CatalogFileLocationRepository catalogFileLocationRepository,
			OrganizationMovementLog organizationMovementLog, OperationLockService operationLockService,
			OrganizationPathValidator organizationPathValidator, SecureFileMove secureFileMove,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.executionRepository = executionRepository;
		this.catalogFileRepository = catalogFileRepository;
		this.catalogFileLocationRepository = catalogFileLocationRepository;
		this.organizationMovementLog = organizationMovementLog;
		this.operationLockService = operationLockService;
		this.organizationPathValidator = organizationPathValidator;
		this.secureFileMove = secureFileMove;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	public OrganizationUndoResponse undo(Long executionId) {
		Execution execution = executionRepository.findById(executionId)
				.orElseThrow(() -> new IllegalArgumentException("Execution not found: " + executionId));

		// Both organization moves and duplicate quarantine moves are plain
		// source->target
		// movements, so the same reversal undoes either; DEDUP_DELETE additionally gets
		// its
		// files flipped back to ACTIVE in updateDatabaseAfterUndo.
		if (execution.getExecutionType() != ExecutionType.ORGANIZATION
				&& execution.getExecutionType() != ExecutionType.DEDUP_DELETE) {
			throw new IllegalArgumentException("Execution is not undoable: " + executionId);
		}

		List<Movement> movements = undoableMovements(execution);

		// Lock every path the undo will touch, not only the execution's own
		// source/target.
		// A DEDUP_DELETE execution locks the quarantine root, but each file is restored
		// to
		// its ORIGINAL path (movement.sourcePath), which lies outside it; without
		// locking
		// those, a concurrent organization on the same tree would race the restore.
		try (var _ = operationLockService.acquire(ExecutionType.UNDO, lockedPaths(execution, movements))) {
			Execution undoExecution = startUndoExecution(execution, movements.size());

			try {
				OrganizationUndoResponse response = undoMovements(execution, undoExecution, movements);

				finishUndoExecution(undoExecution, response);

				return response;
			} catch (RuntimeException undoError) {
				failUndoExecution(undoExecution, undoError.getMessage());

				throw undoError;
			}
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

	private OrganizationUndoResponse undoMovements(Execution execution, Execution undoExecution,
			List<Movement> movements) {
		for (Movement movement : movements) {
			organizationPathValidator.validateAllowed(PathUtils.normalizePath(movement.getSourcePath()), "undo source");
			organizationPathValidator.validateAllowed(PathUtils.normalizePath(movement.getTargetPath()), "undo target");
		}

		List<OrganizationUndoItemResponse> items = new ArrayList<>();

		long undone = 0;
		long skipped = 0;
		long errors = 0;

		for (Movement movement : movements) {
			UndoResult result = undoOne(movement, undoExecution);

			items.add(toItemResponse(movement, result));

			switch (result.status()) {
			case UNDONE -> undone++;
			case SKIPPED -> skipped++;
			case ERROR -> errors++;
			}
		}

		String status = errors > 0 ? "FINISHED_WITH_ERRORS" : "FINISHED";

		String message = "Organization undo finished. undone=" + undone + ", skipped=" + skipped + ", errors=" + errors
				+ ".";

		return new OrganizationUndoResponse(UuidV7.orLegacy(execution.getPublicId(), execution.getId()), status,
				movements.size(), undone, skipped, errors, message, items);
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
			// verify.
			secureFileMove.move(target, source, false);

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
				secureFileMove.rollback(source, target);
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
	 * An undo is an operation of its own: it moves files, it can fail, and until
	 * now it left no trace anyone could look up. Opening an execution gives it a
	 * line on the Execuções screen and gives its failures somewhere to belong.
	 */
	private Execution startUndoExecution(Execution undone, int total) {
		return executionRepository.save(Execution.builder().executionType(ExecutionType.UNDO)
				.status(ExecutionStatus.STARTED).startedAt(LocalDateTime.now(clock))
				.sourcePath(undone.getTargetPath()).targetPath(undone.getSourcePath()).recursive(false)
				.executeFlag(true).statusMessage(StatusMessage.raw(message("backend.undo.started", total)))
				.filesFound(total).filesAnalyzed(0).cacheHits(0).filesMoved(0).simulatedFiles(0).errors(0).build());
	}

	/**
	 * Closes a row whose loop died on the way: an execution still holding a null
	 * {@code finishedAt} is read everywhere as the operation currently running.
	 */
	private void failUndoExecution(Execution undoExecution, String detail) {
		Execution managed = executionRepository.findById(undoExecution.getId()).orElse(undoExecution);

		managed.setStatus(ExecutionStatus.ERROR);
		managed.setFinishedAt(LocalDateTime.now(clock));
		managed.setStatusMessage(StatusMessage.raw(message("backend.execution.operationFailed", detail)));

		executionRepository.save(managed);
	}

	private void finishUndoExecution(Execution undoExecution, OrganizationUndoResponse response) {
		Execution managed = executionRepository.findById(undoExecution.getId()).orElse(undoExecution);

		managed.setStatus(response.errors() > 0 ? ExecutionStatus.FINISHED_WITH_ERRORS : ExecutionStatus.FINISHED);
		managed.setFinishedAt(LocalDateTime.now(clock));
		managed.setFilesAnalyzed((int) (response.undone() + response.skipped() + response.errors()));
		managed.setFilesMoved((int) response.undone());
		managed.setCacheHits((int) response.skipped());
		managed.setErrors((int) response.errors());
		managed.setStatusMessage(StatusMessage
				.raw(message("backend.undo.completed", response.undone(), response.skipped(), response.errors())));

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

	private OrganizationUndoItemResponse toItemResponse(Movement movement, UndoResult result) {
		UUID catalogFileId = movement.getCatalogFile() == null ? null : movement.getCatalogFile().getPublicId();

		return new OrganizationUndoItemResponse(movement.getPublicId(), catalogFileId, movement.getSourcePath(),
				movement.getTargetPath(), result.status().name(), result.message());
	}
}