package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.io.TempDir;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionTotals;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

class ConversionExecutionRecorderTest {

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-07-25T10:15:30Z"), ZoneId.of("UTC"));
	private final ExecutionErrorService executionErrorService = mock(ExecutionErrorService.class);
	private final ConversionExecutionRecorder recorder = new ConversionExecutionRecorder(executionRepository,
			executionErrorService, clock);

	/**
	 * A batch that fails one file used to leave the execution screen reporting "1
	 * error" over an empty list, with no way to tell which of three hundred videos
	 * it was.
	 */
	@Test
	void recordFailureNamesTheFileAndTheReason(@TempDir Path tmp) {
		Execution execution = Execution.builder().id(9L).build();
		Path failed = tmp.resolve("clip.mp4");

		recorder.recordFailure(execution, failed, "output missing");

		verify(executionErrorService).save(failed, ExecutionErrorType.CONVERSION_ERROR, "output missing", execution);
	}

	@Test
	void opensAConversionExecutionForTheFolderTheBatchRunsIn() {
		when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		recorder.start(Path.of("D:", "library"), 3);

		ArgumentCaptor<Execution> saved = ArgumentCaptor.forClass(Execution.class);

		verify(executionRepository).save(saved.capture());

		Assertions.assertThat(saved.getValue().getExecutionType()).isEqualTo(ExecutionType.CONVERSION);
		Assertions.assertThat(saved.getValue().getStatus()).isEqualTo(ExecutionStatus.STARTED);
		Assertions.assertThat(saved.getValue().getSourcePath()).isEqualTo(Path.of("D:", "library").toString());
		Assertions.assertThat(saved.getValue().getFilesFound()).isEqualTo(3);
	}

	@Test
	void acceptsABatchWithNoFolderToRecord() {
		when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		recorder.start(null, 0);

		ArgumentCaptor<Execution> saved = ArgumentCaptor.forClass(Execution.class);

		verify(executionRepository).save(saved.capture());

		Assertions.assertThat(saved.getValue().getSourcePath()).isNull();
	}

	@Test
	void closesTheExecutionWithTheBatchCounters() {
		Execution execution = Execution.builder().id(5L).status(ExecutionStatus.STARTED).build();

		when(executionRepository.findById(5L)).thenReturn(Optional.of(execution));
		when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		recorder.finish(execution, new ConversionTotals(4, 2, 1, 1, 1_000, 400, 600), "done", false);

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FINISHED_WITH_ERRORS);
		Assertions.assertThat(execution.getFilesMoved()).isEqualTo(2);
		Assertions.assertThat(execution.getCacheHits()).isEqualTo(1);
		Assertions.assertThat(execution.getErrors()).isEqualTo(1);
		Assertions.assertThat(execution.getFinishedAt()).isNotNull();
	}

	@Test
	void finishesCleanlyWhenNothingFailed() {
		Execution execution = Execution.builder().id(6L).status(ExecutionStatus.STARTED).build();

		when(executionRepository.findById(6L)).thenReturn(Optional.of(execution));
		when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		recorder.finish(execution, new ConversionTotals(2, 2, 0, 0, 1_000, 400, 600), "done", false);

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
	}

	@Test
	void recordsABatchTheUserStoppedAsCancelled() {
		Execution execution = Execution.builder().id(8L).status(ExecutionStatus.STARTED).build();

		when(executionRepository.findById(8L)).thenReturn(Optional.of(execution));
		when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		// Everything it did manage to convert worked, but it did not run to the end.
		recorder.finish(execution, new ConversionTotals(5, 2, 0, 0, 1_000, 400, 600), "cancelled", true);

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
	}

	@Test
	void stillFinishesWhenTheExecutionRowCanNoLongerBeLoaded() {
		Execution execution = Execution.builder().id(7L).status(ExecutionStatus.STARTED).build();

		when(executionRepository.findById(7L)).thenReturn(Optional.empty());
		when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		recorder.finish(execution, new ConversionTotals(1, 1, 0, 0, 10, 4, 6), "done", false);

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
	}
}