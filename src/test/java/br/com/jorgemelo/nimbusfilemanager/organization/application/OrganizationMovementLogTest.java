package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MovePaths;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;

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

	private final MovementRepository movementRepository = mock(MovementRepository.class);
	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);
	private final ExecutionErrorService executionErrorService = mock(ExecutionErrorService.class);
	private final OrganizationMovementLog log = new OrganizationMovementLog(movementRepository, catalogFileRepository,
			executionErrorService);

	private final Execution execution = Execution.builder().id(4L).build();

	@Test
	void aFailedMoveIsRecordedAsAnExecutionErrorAsWellAsAMovement(@TempDir Path tmp) {
		Path source = tmp.resolve("clip.mp4");

		log.recordMovement(execution, (CatalogFile) null, paths(tmp), MovementStatus.ERROR, MovementReason.IO_ERROR,
				"disk gone", false);

		verify(movementRepository).save(any());
		verify(executionErrorService).save(source, ExecutionErrorType.MOVE_ERROR, "disk gone", execution);
	}

	@Test
	void aMoveThatWorkedRecordsNoError(@TempDir Path tmp) {
		log.recordMovement(execution, (CatalogFile) null, paths(tmp), MovementStatus.MOVED, MovementReason.NONE, null,
				false);

		verify(movementRepository).save(any());
		verify(executionErrorService, never()).save(any(), any(), any(), any());
	}

	/** A simulation writes nothing at all - not the movement, not the error. */
	@Test
	void aDryRunRecordsNothing(@TempDir Path tmp) {
		log.recordMovement(execution, (CatalogFile) null, paths(tmp), MovementStatus.ERROR, MovementReason.IO_ERROR,
				"boom", true);

		verify(movementRepository, never()).save(any());
		verify(executionErrorService, never()).save(any(), any(), any(), any());
	}

	/** An undo failure belongs to the undo, which is an execution of its own. */
	@Test
	void anUndoFailureIsRecordedAgainstTheUndoExecution(@TempDir Path tmp) {
		Path target = tmp.resolve("organized.mp4");
		Execution undoExecution = Execution.builder().id(9L).build();

		Movement movement = Movement.builder().execution(execution).sourcePath(tmp.resolve("clip.mp4").toString())
				.targetPath(target.toString()).status(MovementStatus.MOVED).build();

		log.recordUndoFailure(movement, undoExecution, MovementReason.IO_ERROR, "target is read-only");

		verify(movementRepository).save(movement);
		verify(executionErrorService).save(target, ExecutionErrorType.MOVE_ERROR, "target is read-only",
				undoExecution);
	}

	/**
	 * The reversal is appended, not written over the movement it reverses: the
	 * original keeps the reason it was moved for, and the undo gets a row of its
	 * own in the opposite direction. That is what gives a file moved and undone
	 * repeatedly a history instead of a final state.
	 */
	@Test
	void undoingAppendsTheReverseMovementAndLeavesTheOriginalReasonAlone(@TempDir Path tmp) {
		Execution undoExecution = Execution.builder().id(9L).build();

		Movement original = Movement.builder().execution(execution).sourcePath(tmp.resolve("clip.mp4").toString())
				.targetPath(tmp.resolve("organized.mp4").toString()).status(MovementStatus.MOVED)
				.reason(MovementReason.NONE).build();

		log.recordUndone(original, undoExecution);

		ArgumentCaptor<Movement> saved = ArgumentCaptor.forClass(Movement.class);

		verify(movementRepository, times(2)).save(saved.capture());

		Movement marked = saved.getAllValues().get(0);
		Movement reverse = saved.getAllValues().get(1);

		Assertions.assertThat(marked.getStatus()).isEqualTo(MovementStatus.UNDONE);
		Assertions.assertThat(marked.getReason()).isEqualTo(MovementReason.NONE);

		Assertions.assertThat(reverse.getExecution()).isSameAs(undoExecution);
		Assertions.assertThat(reverse.getSourcePath()).isEqualTo(original.getTargetPath());
		Assertions.assertThat(reverse.getTargetPath()).isEqualTo(original.getSourcePath());
		Assertions.assertThat(reverse.getReason()).isEqualTo(MovementReason.UNDONE_BY_USER);
	}

	private MovePaths paths(Path tmp) {
		return new MovePaths(tmp.resolve("clip.mp4"), tmp.resolve("organized.mp4"));
	}
}