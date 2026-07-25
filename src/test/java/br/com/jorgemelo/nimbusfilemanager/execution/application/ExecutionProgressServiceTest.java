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
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionStepRepository;

@ExtendWith(MockitoExtension.class)
class ExecutionProgressServiceTest {

	@Mock
	private ExecutionRepository executionRepository;

	@Mock
	private ExecutionStepRepository executionStepRepository;

	@Mock
	private ExecutionCancellationService executionCancellationService;

	private final ExecutionMessageCodec messageCodec = new ExecutionMessageCodec(new ObjectMapper());

	@Test
	void updateStatusShouldUpdateManagedExecutionAndPersistStep() {
		Execution execution = execution(1L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		service().updateStatus(execution, ExecutionStatus.PROCESSING_FILES, ExecutionStepType.PROCESSING_STARTED,
				ExecutionMessages.processingFiles());

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.PROCESSING_FILES);
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

		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.PROGRESS_UPDATED
				&& step.getPath() == null
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
		Execution interrupted = execution(2L);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));
		when(executionRepository.findByFinishedAtIsNullAndStatusIn(any())).thenReturn(List.of(interrupted));

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

		service().markInterruptedExecutions();

		Assertions.assertThat(interrupted.getStatus()).isEqualTo(ExecutionStatus.INTERRUPTED);

		verify(executionRepository, never()).save(any());
		verify(executionStepRepository).save(argThat(step -> step.getStepType() == ExecutionStepType.INTERRUPTED));
	}

	@Test
	void markInterruptedExecutionsSkipsExecutionsStillRunningInThisJvm() {
		Execution live = execution(7L);
		live.setStatus(ExecutionStatus.PROCESSING_FILES);

		when(executionRepository.findByFinishedAtIsNullAndStatusIn(any())).thenReturn(List.of(live));
		when(executionCancellationService.isLive(7L)).thenReturn(true);

		service().markInterruptedExecutions();

		// Still running (e.g. an organization) while an inventory starts: its real
		// status must be left
		// alone instead of being falsely flipped to INTERRUPTED (which used to leave
		// its lock stuck).
		Assertions.assertThat(live.getStatus()).isEqualTo(ExecutionStatus.PROCESSING_FILES);
		Assertions.assertThat(live.getFinishedAt()).isNull();

		verify(executionStepRepository, never())
				.save(argThat(step -> step.getStepType() == ExecutionStepType.INTERRUPTED));
	}

	@Test
	void shouldThrowWhenExecutionCannotBeFound() {
		Execution execution = execution(99L);

		when(executionRepository.findById(99L)).thenReturn(Optional.empty());

		assertThatIllegalStateException()
				.isThrownBy(() -> service().updateStatus(execution, ExecutionStatus.ERROR, ExecutionStepType.ERROR,
						ExecutionMessages.inventoryFailed("missing")))
				.withMessage("Execution not found: 99");
	}

	private ExecutionProgressService service() {
		return new ExecutionProgressService(executionRepository, executionStepRepository, executionCancellationService,
				messageCodec, Clock.systemDefaultZone());
	}

	private Execution execution(Long id) {
		return Execution.builder().id(id).executionType(ExecutionType.INVENTORY).status(ExecutionStatus.STARTED)
				.startedAt(LocalDateTime.now()).filesFound(0).filesAnalyzed(0).cacheHits(0).errors(0).build();
	}
}