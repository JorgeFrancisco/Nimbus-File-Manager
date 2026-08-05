package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.ExecutionStepRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

@ExtendWith(MockitoExtension.class)
class ExecutionProgressServiceTest {

	@Mock
	private ExecutionRepository executionRepository;

	@Mock
	private ExecutionStepRepository executionStepRepository;

	private final ExecutionMessageCodec messageCodec = new ExecutionMessageCodec(new ObjectMapper());

	/**
	 * Reconcile fills different counters from every other type: it analyses
	 * nothing, hits no cache and moves no file, so what it can honestly report is
	 * what the walk found and how many entries it corrected.
	 */
	@Test
	void finishReconcileShouldRecordWhatWasFoundAndRepaired() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().finishReconcile(execution, 120, 3, ExecutionMessages.reconcileRepaired(1, 1, 1));

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		Assertions.assertThat(execution.getFilesFound()).isEqualTo(120);
		Assertions.assertThat(execution.getRepairedItems()).isEqualTo(3);
		Assertions.assertThat(execution.getFinishedAt()).isNotNull();

		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.FINISHED));
	}

	@Test
	void finishReconcileShouldRecordZeroWhenNothingWasRepaired() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().finishReconcile(execution, 120, 0, ExecutionMessages.reconcileRepaired(0, 0, 0));

		Assertions.assertThat(execution.getRepairedItems()).isZero();
		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
	}

	/**
	 * A command that acted on a set of items has to say what became of each of
	 * them, and the counter the general {@code finish} cannot write is the one that
	 * matters most here: how many it actually carried out. A deletion of forty
	 * files reporting none moved is a row whose numbers say the opposite of what
	 * happened.
	 */
	@Test
	void finishCommandShouldRecordWhatWasMovedKeptAndFailed() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().finishCommand(execution, ExecutionStatus.FINISHED_WITH_ERRORS, new ExecutionCounts(9, 6, 2, 1),
				ExecutionMessages.operationFailed("parcial"));

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FINISHED_WITH_ERRORS);
		Assertions.assertThat(execution.getFilesFound()).isEqualTo(9);
		Assertions.assertThat(execution.getFilesAnalyzed()).isEqualTo(9);
		Assertions.assertThat(execution.getFilesMoved()).isEqualTo(6);
		Assertions.assertThat(execution.getCacheHits()).isEqualTo(2);
		Assertions.assertThat(execution.getErrors()).isEqualTo(1);
		Assertions.assertThat(execution.getFinishedAt()).isNotNull();

		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.FINISHED));
	}

	@Test
	void updatePhaseShouldUpdateManagedExecutionAndPersistStep() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().updatePhase(execution, ExecutionPhase.PROCESSING, ExecutionStepType.PROCESSING_STARTED,
				ExecutionMessages.processingFiles());

		Assertions.assertThat(execution.getPhase()).isEqualTo(ExecutionPhase.PROCESSING);
		Assertions.assertThat(execution.getStatusMessage().getCode()).isEqualTo(ExecutionMessages.PROCESSING_FILES);
		Assertions.assertThat(execution.getStatusMessage().getText()).isNull();

		verify(executionRepository, never()).save(any());
		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.PROCESSING_STARTED
				&& ExecutionMessages.PROCESSING_FILES.equals(step.getStatusMessage().getCode())));
	}

	@Test
	void cancelShouldMarkExecutionCancelledWithFinishTimeAndMessage() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().cancel(execution, ExecutionMessages.inventoryCancelled());

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
		Assertions.assertThat(execution.getFinishedAt()).isNotNull();
		Assertions.assertThat(execution.getStatusMessage().getCode()).isEqualTo(ExecutionMessages.INVENTORY_CANCELLED);

		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.CANCELLED));
	}

	@Test
	void failShouldMarkExecutionErroredWithFinishTimeAndMessage() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().fail(execution, ExecutionMessages.inventoryFailed("boom"));

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.ERROR);
		Assertions.assertThat(execution.getFinishedAt()).isNotNull();
		Assertions.assertThat(execution.getStatusMessage().getCode()).isEqualTo(ExecutionMessages.INVENTORY_FAILED);
		Assertions.assertThat(execution.getStatusMessage().getArgs()).contains("boom");

		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.ERROR));
	}

	@Test
	void updateProgressAndRecordErrorShouldStoreCountersAndPaths() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().updateProgress(execution, 2, 1, 1, 0, Path.of("C:/input/photo.jpg"));
		service().recordError(execution, Path.of("C:/input/bad.jpg"), "bad", 3, 1, 1, 1);

		Assertions.assertThat(execution.getFilesFound()).isEqualTo(3);
		Assertions.assertThat(execution.getErrors()).isEqualTo(1);
		Assertions.assertThat(execution.getStatusMessage().getCode())
				.isEqualTo(ExecutionMessages.ERROR_PROCESSING_FILE);

		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.FILE_ERROR
				&& step.getPath().endsWith(Path.of("input", "bad.jpg").toString())
				&& "bad".equals(step.getStatusMessage().getText())));
	}

	@Test
	void updateProgressWithoutAFileFallsBackToTheProgressUpdatedCode() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().updateProgress(execution, 4, 2, 1, 0, (Path) null);

		Assertions.assertThat(execution.getFilesFound()).isEqualTo(4);
		Assertions.assertThat(execution.getStatusMessage().getCode()).isEqualTo(ExecutionMessages.PROGRESS_UPDATED);
		Assertions.assertThat(execution.getStatusMessage().getArgs()).isNull();

		verify(executionStepRepository)
				.save(argThat(step -> step.getStepType() == ExecutionStepType.PROGRESS_UPDATED && step.getPath() == null
						&& ExecutionMessages.PROGRESS_UPDATED.equals(step.getStatusMessage().getCode())));
	}

	@Test
	void updateLiveProgressSetsCountersAndMessageWithoutRecordingAStep() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().updateLiveProgress(execution, 25, 10, 3, 1, ExecutionMessages.extractingMetadata());

		Assertions.assertThat(execution.getFilesFound()).isEqualTo(25);
		Assertions.assertThat(execution.getFilesAnalyzed()).isEqualTo(10);
		Assertions.assertThat(execution.getStatusMessage().getCode()).isEqualTo(ExecutionMessages.EXTRACTING_METADATA);

		verify(executionStepRepository, never()).save(any());
	}

	@Test
	void recordErrorWithoutAFileKeepsTheRawErrorAsALegacyMessage() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().recordError(execution, null, "unexpected failure", 5, 3, 1, 1);

		Assertions.assertThat(execution.getStatusMessage().getCode()).isNull();
		Assertions.assertThat(execution.getStatusMessage().getArgs()).isNull();
		Assertions.assertThat(execution.getStatusMessage().getText()).isEqualTo("unexpected failure");

		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.FILE_ERROR
				&& step.getPath() == null && "unexpected failure".equals(step.getStatusMessage().getText())));
	}

	@Test
	void updateTotalShouldStoreTotalExpected() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().updateTotal(execution, 150000);

		Assertions.assertThat(execution.getTotalExpected()).isEqualTo(150000);
	}

	@Test
	void updateProgressWithTextShouldStoreCountersAndMessageWithoutAPath() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().updateProgress(execution, 10, 6, 3, 1, "C:/input/current-item.jpg");
		service().updateProgress(execution, 12, 7, 3, 1, (String) null);

		Assertions.assertThat(execution.getFilesFound()).isEqualTo(12);
		Assertions.assertThat(execution.getFilesAnalyzed()).isEqualTo(7);

		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.PROGRESS_UPDATED
				&& step.getPath() == null && ExecutionMessages.PROCESSING_ITEM.equals(step.getStatusMessage().getCode())
				&& step.getStatusMessage().getArgs().contains("C:/input/current-item.jpg")));
		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.PROGRESS_UPDATED
				&& ExecutionMessages.PROGRESS_UPDATED.equals(step.getStatusMessage().getCode())));
	}

	@Test
	void finishFailAndInterruptedExecutionsShouldUpdateStatus() {
		Execution execution = execution(1L);
		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().finish(execution, ExecutionStatus.FINISHED, 5, 4, 1, 0, ExecutionMessages.inventoryCompleted());

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		Assertions.assertThat(execution.getFinishedAt()).isNotNull();

		service().fail(execution, ExecutionMessages.inventoryFailed("failed"));

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.ERROR);

		service().cancel(execution, ExecutionMessages.inventoryCancelled());

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
		Assertions.assertThat(execution.getFinishedAt()).isNotNull();

		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.CANCELLED
				&& ExecutionMessages.INVENTORY_CANCELLED.equals(step.getStatusMessage().getCode())));

		service().interrupt(execution, ExecutionMessages.executionInterrupted());

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.INTERRUPTED);

		verify(executionRepository, never()).save(any());
		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.INTERRUPTED));
	}

	@Test
	void shouldThrowWhenExecutionCannotBeFound() {
		Execution execution = execution(99L);

		when(executionRepository.findById(99L)).thenReturn(Optional.empty());

		assertThatIllegalStateException().isThrownBy(() -> service().updatePhase(execution, ExecutionPhase.PROCESSING,
				ExecutionStepType.ERROR, ExecutionMessages.inventoryFailed("missing")))
				.withMessage("Execution not found: 99");
	}


	/**
	 * Two endings that are not failures and must not be told apart by reading a
	 * sentence: an execution whose locks went away stopped through no fault of the
	 * work, and one the product refused never ran at all. Somebody reading the
	 * history to ask "did my cancel work?" has to be able to tell all three apart
	 * by status alone.
	 */
	@Test
	void interruptedAndRejectedEndingsCarryTheirOwnStatusAndStep() {
		Execution interrupted = execution(1L);
		Execution rejected = execution(2L);

		interrupted.setCurrentItemPercent(40);
		rejected.setCurrentItemPercent(70);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(interrupted));
		when(executionRepository.findById(2L)).thenReturn(Optional.of(rejected));

		service().interrupt(interrupted, ExecutionMessages.executionInterrupted());

		Assertions.assertThat(interrupted.getStatus()).isEqualTo(ExecutionStatus.INTERRUPTED);
		Assertions.assertThat(interrupted.getFinishedAt()).isNotNull();

		// Whatever the run was halfway through is over: leaving the column set would
		// show the old item still advancing on every screen that reads the row.
		Assertions.assertThat(interrupted.getCurrentItemPercent()).isNull();

		service().reject(rejected, ExecutionMessages.executionSuperseded());

		Assertions.assertThat(rejected.getStatus()).isEqualTo(ExecutionStatus.REJECTED);
		Assertions.assertThat(rejected.getFinishedAt()).isNotNull();
		Assertions.assertThat(rejected.getCurrentItemPercent()).isNull();

		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.INTERRUPTED));
		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.REJECTED));
	}

	/**
	 * A row with no id has nothing to throttle against, and the item progress is
	 * an optimisation - never a reason to fail the work that reported it.
	 */
	@Test
	void ignoresItemProgressForAnExecutionThatHasNoRowYet() {
		service().updateCurrentItem(Execution.builder().executionType(ExecutionType.CONVERSION).build(), 30);

		verify(executionRepository, never()).save(any());
	}

	private ExecutionProgressService service() {
		return new ExecutionProgressService(executionRepository,
				new ExecutionItemProgressWriter(executionRepository), executionStepRepository, messageCodec,
				Clock.systemDefaultZone());
	}

	private Execution execution(Long id) {
		return Execution.builder().id(id).executionType(ExecutionType.INVENTORY).status(ExecutionStatus.RUNNING)
				.startedAt(LocalDateTime.now()).filesFound(0).filesAnalyzed(0).cacheHits(0).errors(0).build();
	}
}