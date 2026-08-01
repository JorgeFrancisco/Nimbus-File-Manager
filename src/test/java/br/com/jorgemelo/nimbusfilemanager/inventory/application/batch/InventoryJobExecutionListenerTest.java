package br.com.jorgemelo.nimbusfilemanager.inventory.application.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintBacklogResumer;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.scanner.FileScanner;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingMetrics;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionPhaseTimings;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.PerformanceTelemetryService;

@ExtendWith(MockitoExtension.class)
class InventoryJobExecutionListenerTest {

	@Mock
	private ExecutionRepository executionRepository;

	@Mock
	private ExecutionProgressService executionProgressService;

	@Mock
	private ExecutionCancellationService executionCancellationService;

	@Mock
	private FileScanner fileScanner;

	@Mock
	private ScanExclusionService scanExclusionService;

	@Mock
	private PerformanceTelemetryService performanceTelemetryService;

	@Test
	void beforeJobShouldRegisterCancellationCountFilesAndMoveToProcessing() {
		Execution execution = Execution.builder().id(5L).build();

		when(executionRepository.findById(5L)).thenReturn(Optional.of(execution));
		when(scanExclusionService.excludedExtensions()).thenReturn(List.of());
		when(scanExclusionService.excludedFolders()).thenReturn(List.of());
		when(fileScanner.count(any(), any())).thenReturn(42L);

		listener().beforeJob(jobExecution(BatchStatus.STARTING));

		verify(executionCancellationService).register(5L);
		verify(executionProgressService).updateStatus(execution, ExecutionStatus.SCANNING_FILES,
				ExecutionStepType.SCANNING_STARTED, ExecutionMessages.countingFiles());
		verify(executionProgressService).updateTotal(execution, 42);
		verify(executionProgressService).updateStatus(execution, ExecutionStatus.PROCESSING_FILES,
				ExecutionStepType.PROCESSING_STARTED, ExecutionMessages.processingFiles());
	}

	@Test
	void afterJobShouldFinishWithoutErrorsWhenCompletedCleanly() {
		Execution execution = Execution.builder().id(5L).filesFound(10).filesAnalyzed(10).cacheHits(0).errors(0)
				.build();

		when(executionRepository.findById(5L)).thenReturn(Optional.of(execution));
		when(executionCancellationService.isCancelled(5L)).thenReturn(false);

		listener().afterJob(jobExecution(BatchStatus.COMPLETED));

		verify(executionCancellationService).unregister(5L);
		verify(executionProgressService).finish(execution, ExecutionStatus.FINISHED, 10, 10, 0, 0,
				ExecutionMessages.inventoryCompleted());
	}

	@Test
	void afterJobShouldFinishWithErrorsWhenCompletedWithRecordedErrors() {
		Execution execution = Execution.builder().id(5L).filesFound(10).filesAnalyzed(8).cacheHits(0).errors(2).build();

		when(executionRepository.findById(5L)).thenReturn(Optional.of(execution));
		when(executionCancellationService.isCancelled(5L)).thenReturn(false);

		listener().afterJob(jobExecution(BatchStatus.COMPLETED));

		verify(executionProgressService).finish(execution, ExecutionStatus.FINISHED_WITH_ERRORS, 10, 8, 0, 2,
				ExecutionMessages.inventoryCompleted());
	}

	@Test
	void afterJobShouldCancelWhenUserRequestedCancellation() {
		Execution execution = Execution.builder().id(5L).build();

		when(executionRepository.findById(5L)).thenReturn(Optional.of(execution));
		when(executionCancellationService.isCancelled(5L)).thenReturn(true);

		listener().afterJob(jobExecution(BatchStatus.FAILED));

		verify(executionProgressService).cancel(execution, ExecutionMessages.inventoryCancelled());
	}

	@Test
	void afterJobShouldRejectWhenFailureIsAnOperationLockException() {
		Execution execution = Execution.builder().id(5L).build();

		when(executionRepository.findById(5L)).thenReturn(Optional.of(execution));
		when(executionCancellationService.isCancelled(5L)).thenReturn(false);

		JobExecution jobExecution = jobExecution(BatchStatus.FAILED);

		jobExecution.addFailureException(new OperationLockException("Another INVENTORY execution is running."));

		listener().afterJob(jobExecution);

		verify(executionProgressService).fail(execution,
				ExecutionMessages.inventoryRejected("Another INVENTORY execution is running."));
	}

	@Test
	void afterJobShouldFailWithRootMessageForGenericFailures() {
		Execution execution = Execution.builder().id(5L).build();

		when(executionRepository.findById(5L)).thenReturn(Optional.of(execution));
		when(executionCancellationService.isCancelled(5L)).thenReturn(false);

		JobExecution jobExecution = jobExecution(BatchStatus.FAILED);

		jobExecution.addFailureException(new IllegalStateException("boom"));

		listener().afterJob(jobExecution);

		verify(executionProgressService).fail(execution, ExecutionMessages.inventoryFailed("boom"));
	}

	@Test
	void afterJobShouldFailWithUnknownErrorWhenNoFailureExceptionIsRecorded() {
		Execution execution = Execution.builder().id(5L).build();

		when(executionRepository.findById(5L)).thenReturn(Optional.of(execution));
		when(executionCancellationService.isCancelled(5L)).thenReturn(false);

		listener().afterJob(jobExecution(BatchStatus.FAILED));

		verify(executionProgressService).fail(execution, ExecutionMessages.inventoryFailed("Unknown error."));
	}

	private final FingerprintBacklogResumer fingerprintBacklogResumer = mock(FingerprintBacklogResumer.class);

	/**
	 * Both backlogs stand aside while an inventory runs - the video one is
	 * cancelled mid-drain - and the inventory is what knows it is over. Resuming
	 * only the photo backlog left video hashing stopped until the next restart.
	 */
	@Test
	void afterJobResumesBothFingerprintBacklogs() {
		Execution execution = Execution.builder().id(5L).filesFound(10).filesAnalyzed(10).cacheHits(0).errors(0)
				.build();

		when(executionRepository.findById(5L)).thenReturn(Optional.of(execution));
		when(executionCancellationService.isCancelled(5L)).thenReturn(false);

		listener().afterJob(jobExecution(BatchStatus.COMPLETED));

		verify(fingerprintBacklogResumer).resume();
	}

	private InventoryJobExecutionListener listener() {
		InventoryTelemetryRecorder telemetryRecorder = new InventoryTelemetryRecorder(new ProcessingMetrics(),
				new ExecutionPhaseTimings(), performanceTelemetryService,
				new ProcessingProperties(null, null, null, null, null, 1));

		return new InventoryJobExecutionListener(executionRepository, executionProgressService,
				executionCancellationService, fileScanner, scanExclusionService, fingerprintBacklogResumer,
				telemetryRecorder);
	}

	private JobExecution jobExecution(BatchStatus status) {
		JobParameters parameters = new JobParametersBuilder().addLong("executionId", 5L)
				.addString("sourcePath", "C:/media").addString("recursive", "true").addString("includeHidden", "false")
				.addString("calculateHashes", "true").addString("forceAnalysis", "false").toJobParameters();

		JobExecution jobExecution = new JobExecution(new JobInstance(1L, "inventoryJob"), 100L, parameters);

		jobExecution.setStatus(status);

		return jobExecution;
	}
}