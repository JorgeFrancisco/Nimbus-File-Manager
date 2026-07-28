package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MovePaths;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * The movement rows of an organization: what was attempted for each file, and -
 * when it failed - the execution error that names it.
 *
 * <p>
 * The two belong together. The movement records the relocation that was
 * attempted, where from and where to, which is what an undo reads; the
 * execution error records why a file could not be processed, which is the one
 * place every screen looks for that. Owning both here spares the executor and
 * the undo service a dependency each: they ask for the record, not for the
 * tables behind it.
 */
@Slf4j
@Component
public class OrganizationMovementLog {

	private final MovementRepository movementRepository;
	private final CatalogFileRepository catalogFileRepository;
	private final ExecutionErrorService executionErrorService;

	public OrganizationMovementLog(MovementRepository movementRepository,
			CatalogFileRepository catalogFileRepository, ExecutionErrorService executionErrorService) {
		this.movementRepository = movementRepository;
		this.catalogFileRepository = catalogFileRepository;
		this.executionErrorService = executionErrorService;
	}

	/** Resolves the media file first; a dry run resolves and writes nothing. */
	public void recordMovement(Execution execution, Long catalogFileId, MovePaths paths, MovementStatus status,
			MovementReason reason, String errorMessage, boolean dryRun) {
		// Resolving the media file is a read; skip it too in dry-run so a simulation is
		// pure - it changes nothing, and the movement row it would feed is not written.
		if (dryRun) {
			return;
		}

		recordMovement(execution, catalogFileRepository.findById(catalogFileId).orElse(null), paths, status, reason,
				errorMessage, dryRun);
	}

	public void recordMovement(Execution execution, CatalogFile catalogFile, MovePaths paths, MovementStatus status,
			MovementReason reason, String errorMessage, boolean dryRun) {
		// Side-effect choke point: in dry-run no Movement row is ever persisted.
		if (dryRun) {
			return;
		}

		movementRepository.save(Movement.builder().execution(execution).catalogFile(catalogFile)
				.sourcePath(PathUtils.normalize(paths.source())).targetPath(PathUtils.normalize(paths.target()))
				.status(status).reason(reason).build());

		if (status == MovementStatus.ERROR) {
			executionErrorService.save(Path.of(PathUtils.normalize(paths.source())), ExecutionErrorType.MOVE_ERROR,
					errorMessage, execution);
		}
	}

	/** The movements an undo may still reverse, newest first. */
	public List<Movement> undoable(Long executionId) {
		return movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(executionId,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR));
	}

	/**
	 * A file put back where it came from. The movement being reversed keeps its
	 * own story - the reason it was moved is not erased - and the reversal is
	 * appended as a movement of its own, in the opposite direction, belonging to
	 * the undo. A file organized and undone three times leaves six rows in order
	 * instead of one row rewritten three times.
	 */
	public void recordUndone(Movement undone, Execution undoExecution) {
		undone.setStatus(MovementStatus.UNDONE);

		movementRepository.save(undone);

		movementRepository.save(Movement.builder().execution(undoExecution).catalogFile(undone.getCatalogFile())
				.sourcePath(undone.getTargetPath()).targetPath(undone.getSourcePath()).status(MovementStatus.MOVED)
				.reason(MovementReason.UNDONE_BY_USER).build());
	}

	/**
	 * An undo that could not put a file back: the failure belongs to the undo, and
	 * the reason lives with every other per-file failure instead of on the
	 * movement row.
	 */
	public void recordUndoFailure(Movement movement, Execution undoExecution, MovementReason reason, String message) {
		movement.setStatus(MovementStatus.UNDO_ERROR);
		movement.setReason(reason);

		movementRepository.save(movement);

		executionErrorService.save(Path.of(movement.getTargetPath()), ExecutionErrorType.MOVE_ERROR, message,
				undoExecution);
	}

	/**
	 * Records a failure without letting the recording itself break the run: an
	 * organization that cannot write its audit row still has files to move.
	 */
	public void recordSafely(Execution execution, CatalogFile catalogFile, Long catalogFileId, MovePaths paths,
			MovementReason reason, String message, boolean dryRun) {
		try {
			if (catalogFile == null) {
				recordMovement(execution, catalogFileId, paths, MovementStatus.ERROR, reason, message, dryRun);
			} else {
				recordMovement(execution, catalogFile, paths, MovementStatus.ERROR, reason, message, dryRun);
			}
		} catch (Exception e) {
			log.error("Could not record movement error. catalogFileId={} source={} target={}", catalogFileId,
					paths.source(), paths.target(), e);
		}
	}
}