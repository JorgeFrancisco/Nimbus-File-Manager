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
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionAdjustments;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionFileResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionTotals;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionOutcome;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.model.ConversionItemResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionMessages;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.ConversionItemResultRepository;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

class ConversionExecutionRecorderTest {

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-07-25T10:15:30Z"), ZoneId.of("UTC"));
	private final ExecutionErrorService executionErrorService = mock(ExecutionErrorService.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);
	private final ConversionItemResultRepository conversionItemResultRepository = mock(
			ConversionItemResultRepository.class);
	private final ConversionExecutionRecorder recorder = new ConversionExecutionRecorder(executionRepository,
			executionErrorService, conversionItemResultRepository, executionProgressService, clock);

	/**
	 * The report is written a line at a time, as each file is decided, rather than
	 * collected in memory and saved at the end: a batch that runs for hours in
	 * another process has to be readable while it runs, and one that dies halfway
	 * has to leave behind what it did instead of nothing.
	 */
	@Test
	void keepsOneLineOfTheReportForEachFileAsItIsDecided() {
		Execution execution = Execution.builder().id(7L).executionType(ExecutionType.CONVERSION).build();

		UUID media = UUID.randomUUID();

		recorder.recordItem(execution, new ConversionFileResult(media, "holiday.mp4", ConversionOutcome.CONVERTED,
				1_000L, 400L, 600L, "600 B", 1_200L, new ConversionAdjustments(true, false, true), true, "done"));

		ArgumentCaptor<ConversionItemResult> captor = ArgumentCaptor.forClass(ConversionItemResult.class);

		verify(conversionItemResultRepository).save(captor.capture());

		ConversionItemResult line = captor.getValue();

		Assertions.assertThat(line.getExecution()).isSameAs(execution);
		Assertions.assertThat(line.getMediaPublicId()).isEqualTo(media);
		Assertions.assertThat(line.getFileName()).isEqualTo("holiday.mp4");
		Assertions.assertThat(line.getOutcome()).isEqualTo(ConversionOutcome.CONVERTED);
		Assertions.assertThat(line.getOriginalBytes()).isEqualTo(1_000L);
		Assertions.assertThat(line.getConvertedBytes()).isEqualTo(400L);
		Assertions.assertThat(line.getMessage()).isEqualTo("done");

		// The three adjustments travel apart from the outcome: a file can convert and
		// still have lost its subtitles, and the report says so.
		Assertions.assertThat(line.getAudioFallback()).isTrue();
		Assertions.assertThat(line.getSubtitlesDropped()).isFalse();
		Assertions.assertThat(line.getDataDropped()).isTrue();
		Assertions.assertThat(line.getOriginalQuarantined()).isTrue();
	}

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

	/**
	 * A row left with a null {@code finishedAt} is read everywhere as the operation
	 * currently running, so a batch that died has to close its own row.
	 */
	@Test
	void failClosesTheRowSoNoPhantomBatchIsLeftRunning() {
		Execution execution = Execution.builder().id(9L).build();

		when(executionRepository.findById(9L)).thenReturn(Optional.of(execution));
		when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		recorder.fail(execution, "encoder vanished");

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.ERROR);
		Assertions.assertThat(execution.getFinishedAt()).isNotNull();
	}

	@Test
	void closesTheExecutionWithTheBatchCounters() {
		Execution execution = Execution.builder().id(5L).status(ExecutionStatus.RUNNING).build();

		when(executionRepository.findById(5L)).thenReturn(Optional.of(execution));
		when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		recorder.finish(execution, new ConversionTotals(4, 2, 1, 1, 1_000, 400, 600), ConversionMessages.completed(0, 0,
				0,
				"done"),
				false);

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FINISHED_WITH_ERRORS);
		Assertions.assertThat(execution.getFilesMoved()).isEqualTo(2);
		Assertions.assertThat(execution.getCacheHits()).isEqualTo(1);
		Assertions.assertThat(execution.getErrors()).isEqualTo(1);
		Assertions.assertThat(execution.getFinishedAt()).isNotNull();
	}

	@Test
	void finishesCleanlyWhenNothingFailed() {
		Execution execution = Execution.builder().id(6L).status(ExecutionStatus.RUNNING).build();

		when(executionRepository.findById(6L)).thenReturn(Optional.of(execution));
		when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		recorder.finish(execution, new ConversionTotals(2, 2, 0, 0, 1_000, 400, 600), ConversionMessages.completed(0, 0,
				0,
				"done"),
				false);

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
	}

	@Test
	void recordsABatchTheUserStoppedAsCancelled() {
		Execution execution = Execution.builder().id(8L).status(ExecutionStatus.RUNNING).build();

		when(executionRepository.findById(8L)).thenReturn(Optional.of(execution));
		when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		// Everything it did manage to convert worked, but it did not run to the end.
		recorder.finish(execution, new ConversionTotals(5, 2, 0, 0, 1_000, 400, 600), ConversionMessages.completed(0, 0,
				0,
				"cancelled"),
				true);

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
	}

	@Test
	void stillFinishesWhenTheExecutionRowCanNoLongerBeLoaded() {
		Execution execution = Execution.builder().id(7L).status(ExecutionStatus.RUNNING).build();

		when(executionRepository.findById(7L)).thenReturn(Optional.empty());
		when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		recorder.finish(execution, new ConversionTotals(1, 1, 0, 0, 10, 4, 6), ConversionMessages.completed(0, 0, 0,
				"done"),
				false);

		Assertions.assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
	}
}