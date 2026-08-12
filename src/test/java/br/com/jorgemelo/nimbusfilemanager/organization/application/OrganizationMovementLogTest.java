package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.ArgumentMatchers.anyLong;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.MovementWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.PreparedMovements;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MovePaths;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

/**
 * The movement row records what was attempted; the execution error records why
 * it failed. Organization kept the reason on the movement alone, so the
 * execution screen listed per-file failures for every other operation and not
 * for this one.
 *
 * <p>
 * Paths come from {@code @TempDir} because the log normalizes what it stores: a
 * drive literal would be a relative single-segment path on the Linux CI and the
 * assertion would compare against the runner's working directory.
 */
class OrganizationMovementLogTest {

	/**
	 * A real directory, because a prepared movement carries paths that other
	 * capabilities normalise and act on - a relative one would resolve against the
	 * project itself.
	 */
	@TempDir
	Path tempDir;

	private final MovementRepository movementRepository = mock(MovementRepository.class);
	private final MovementWriter movementWriter = mock(MovementWriter.class);
	private final ExecutionErrorService executionErrorService = mock(ExecutionErrorService.class);
	private final OrganizationMovementLog log = new OrganizationMovementLog(movementRepository, movementWriter,
			executionErrorService);

	private final Execution execution = Execution.builder().id(4L).build();

	@Test
	void aFailedMoveIsRecordedAsAnExecutionErrorAsWellAsAMovement(@TempDir Path tmp) {
		Path source = tmp.resolve("clip.mp4");

		log.recordFailure(execution, prepared(), paths(tmp), MovementReason.IO_ERROR, "disk gone");

		verify(movementWriter).markFailed(eq(execution.getId()), any(), eq(MovementReason.IO_ERROR));
		verify(executionErrorService).save(source, ExecutionErrorType.MOVE_ERROR, "disk gone", execution);
	}

	@Test
	void aMoveThatWorkedRecordsNoError() {
		log.recordSkipped(execution, prepared(), MovementReason.ALREADY_MOVED);

		verify(movementWriter).markSkipped(eq(execution.getId()), any(), eq(MovementReason.ALREADY_MOVED));
		verify(executionErrorService, never()).save(any(), any(), any(), any());
	}

	/** A dry run prepares nothing, so there is no operation to settle later. */
	@Test
	void aDryRunPreparesNothing() {
		Assertions.assertThat(log.prepare(execution, List.of(), true)).isEmpty();

		verify(movementWriter, never()).prepare(anyLong(), any());
		verify(executionErrorService, never()).save(any(), any(), any(), any());
	}

	/**
	 * An undo failure belongs to the undo, which is an execution of its own - and
	 * to the reversing operation, which is the one that failed. The operation it
	 * was reversing is left exactly as it was: it did move the file, which is
	 * still true.
	 */
	@Test
	void anUndoFailureIsRecordedAgainstTheReversingOperation(@TempDir Path tmp) {
		Path target = tmp.resolve("organized.mp4");

		Execution undoExecution = Execution.builder().id(9L).build();

		PreparedMovement reversal = prepared();

		log.recordUndoFailure(undoExecution, reversal, target, MovementReason.IO_ERROR, "target is read-only");

		verify(movementWriter).markFailed(9L, List.of(reversal.movementPublicId()), MovementReason.IO_ERROR);
		verify(movementWriter, never()).markUndone(any());
		verify(executionErrorService).save(target, ExecutionErrorType.MOVE_ERROR, "target is read-only", undoExecution);
	}

	/**
	 * Two operations settle and they belong to two different runs. The reversing
	 * one moved a file and says so; the one being reversed keeps its own story -
	 * its identity, its reserved fact and the moment it moved are all still true -
	 * and only records that its effect no longer stands. That is what gives a file
	 * moved and undone repeatedly a history instead of a final state.
	 */
	@Test
	void undoingSettlesTheReversalAndOnlyMarksTheOriginalAsNoLongerStanding(@TempDir Path tmp) {
		Execution undoExecution = Execution.builder().id(9L).build();

		PreparedMovement reversal = prepared();

		Movement original = Movement.builder().movementPublicId(UuidV7.generate()).execution(execution)
				.requestedSourcePath(tmp.resolve("clip.mp4").toString())
				.requestedTargetPath(tmp.resolve("organized.mp4").toString()).status(MovementStatus.MOVED)
				.reason(MovementReason.NONE).build();

		log.recordUndone(undoExecution, reversal, original);

		verify(movementWriter).markMoved(9L, List.of(reversal.movementPublicId()));
		verify(movementWriter).markUndone(List.of(original.getMovementPublicId()));

		Assertions.assertThat(original.getReason()).as("the original keeps the reason it was moved for")
				.isEqualTo(MovementReason.NONE);
	}

	private MovePaths paths(Path tmp) {
		return new MovePaths(tmp.resolve("clip.mp4"), tmp.resolve("organized.mp4"));
	}

	/** The movement the door reserved before the file moved. */
	private PreparedMovement prepared() {
		return PreparedMovements.pending(1L, 7L, tempDir.resolve("de.mp4"), tempDir.resolve("para.mp4"));
	}
}