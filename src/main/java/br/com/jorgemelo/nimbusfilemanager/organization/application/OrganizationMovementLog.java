package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MovePaths;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.MovementRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.MovementWriter;
import lombok.extern.slf4j.Slf4j;

/**
 * How an organization run talks about its operations.
 *
 * <p>
 * It used to write the movement rows itself, after each file had already moved.
 * That role is gone: {@code MovementWriter} owns preparing and settling now, and
 * a row written after the effect could not have reserved the identity the fact
 * needs. What is left here is the part that is about <em>organizing</em> rather
 * than about movements - which operations an undo may still reverse, and the
 * fact that a failed one has to appear in the execution's error list as well as
 * on its own row.
 *
 * <p>
 * Not a facade over the writer: every method here decides something the writer
 * has no business knowing.
 */
@Slf4j
@Component
public class OrganizationMovementLog {

	private final MovementRepository movementRepository;
	private final MovementWriter movementWriter;
	private final ExecutionErrorService executionErrorService;

	public OrganizationMovementLog(MovementRepository movementRepository, MovementWriter movementWriter,
			ExecutionErrorService executionErrorService) {
		this.movementRepository = movementRepository;
		this.movementWriter = movementWriter;
		this.executionErrorService = executionErrorService;
	}

	/**
	 * Every operation this run is about to attempt, written down before the first
	 * file is touched and keyed by the file it is for.
	 *
	 * <p>
	 * One statement for the whole plan, and idempotent: an execution reclaimed
	 * after a worker died prepares again and gets back exactly what its first
	 * attempt decided, identities included. A dry run prepares nothing - it is a
	 * simulation, and an operation on record is not a simulation of anything.
	 */
	public Map<Long, PreparedMovement> prepare(Execution execution, List<MovementRequest> requests, boolean dryRun) {
		if (dryRun || requests.isEmpty()) {
			return Map.of();
		}

		return movementWriter.prepare(execution.getId(), requests).stream()
				.collect(Collectors.toMap(PreparedMovement::catalogFileId, Function.identity()));
	}

	/** The operation decided against moving the file, and the reason says why. */
	public void recordSkipped(Execution execution, PreparedMovement operation, MovementReason reason) {
		if (operation == null) {
			return;
		}

		movementWriter.markSkipped(execution.getId(), List.of(operation.movementPublicId()), reason);
	}

	/**
	 * The operation failed. Two records rather than one, and deliberately: the
	 * movement says this file's operation ended in failure, and the execution's
	 * error list is where every screen looks to find out which files failed and
	 * why. Neither answers the other's question.
	 */
	public void recordFailure(Execution execution, PreparedMovement operation, MovePaths paths,
			MovementReason reason, String message) {
		if (operation != null) {
			movementWriter.markFailed(execution.getId(), List.of(operation.movementPublicId()), reason);
		}

		executionErrorService.save(paths.source(), ExecutionErrorType.MOVE_ERROR, message, execution);
	}

	/**
	 * Records a failure without letting the recording itself break the run: an
	 * organization that cannot write its audit row still has files to move.
	 */
	public void recordFailureSafely(Execution execution, PreparedMovement operation, MovePaths paths,
			MovementReason reason, String message) {
		try {
			recordFailure(execution, operation, paths, reason, message);
		} catch (Exception e) {
			log.error("Could not record a failed operation. source={} target={}", paths.source(), paths.target(), e);
		}
	}

	/** The operations an undo may still reverse, newest first. */
	public List<Movement> undoable(Long executionId) {
		return movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(executionId,
				List.of(MovementStatus.MOVED));
	}

	/**
	 * A file is back where it came from.
	 *
	 * <p>
	 * Two operations settle, and they belong to two different runs. The reversing
	 * one moved a file and says so; the one being reversed keeps its own story
	 * intact - its identity, its reserved fact and the moment it moved are all
	 * still true - and only records that its effect no longer stands. When the
	 * reversal happened is the reversing operation's {@code moved_at}, which is
	 * why there is no second timestamp here.
	 */
	public void recordUndone(Execution undoExecution, PreparedMovement reversal, Movement reversed) {
		movementWriter.markMoved(undoExecution.getId(), List.of(reversal.movementPublicId()));

		movementWriter.markUndone(List.of(reversed.getMovementPublicId()));
	}

	/**
	 * An undo that could not put a file back.
	 *
	 * <p>
	 * The operation being reversed is left exactly as it was, and that is the
	 * correction rather than an omission: it did move the file, which is still
	 * true. Writing the undo's outcome onto its row put the result of one operation
	 * on the record of another - which is what the state {@code UNDO_ERROR} was,
	 * and why it is gone.
	 */
	public void recordUndoFailure(Execution undoExecution, PreparedMovement reversal, Path source,
			MovementReason reason, String message) {
		if (reversal != null) {
			movementWriter.markFailed(undoExecution.getId(), List.of(reversal.movementPublicId()), reason);
		}

		executionErrorService.save(source, ExecutionErrorType.MOVE_ERROR, message, undoExecution);
	}
}