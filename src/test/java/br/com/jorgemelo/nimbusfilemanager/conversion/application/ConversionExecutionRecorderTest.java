package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionMessages;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionAdjustments;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionFileResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionTotals;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionOutcome;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.model.ConversionItemResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.ConversionItemResultRepository;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

class ConversionExecutionRecorderTest {

	private final ExecutionErrorService executionErrorService = mock(ExecutionErrorService.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);
	private final ConversionItemResultRepository conversionItemResultRepository = mock(
			ConversionItemResultRepository.class);
	private final ConversionExecutionRecorder recorder = new ConversionExecutionRecorder(executionErrorService,
			conversionItemResultRepository, executionProgressService);

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
				1_000L, 400L, 600L, "600 B", 1_200L, new ConversionAdjustments(true, false, true), true, false,
				"done"));

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
	 * currently running, so a batch that died has to close its own row - and it
	 * does so under the taking it ran as, which is what keeps a batch that came
	 * back late from stamping its outcome over whoever holds the row now.
	 */
	@Test
	void failClosesTheRowUnderTheTakingTheBatchRanAs() {
		ExecutionOwnership ownership = Takings.owning(9L);

		recorder.fail(ownership, "encoder vanished");

		verify(executionProgressService).fail(ownership, ConversionMessages.failed("encoder vanished"));
	}

	@Test
	void closesTheExecutionWithTheBatchCounters() {
		ExecutionOwnership ownership = Takings.owning(5L);

		recorder.finish(ownership, new ConversionTotals(4, 2, 1, 1, 1_000, 400, 600),
				ConversionMessages.completed(0, 0, 0, "done"), false);

		verify(executionProgressService).finishCommand(ownership, ExecutionStatus.FINISHED_WITH_ERRORS,
				new ExecutionCounts(4, 2, 1, 1), ConversionMessages.completed(0, 0, 0, "done"));
	}

	@Test
	void finishesCleanlyWhenNothingFailed() {
		ExecutionOwnership ownership = Takings.owning(6L);

		recorder.finish(ownership, new ConversionTotals(2, 2, 0, 0, 1_000, 400, 600),
				ConversionMessages.completed(0, 0, 0, "done"), false);

		verify(executionProgressService).finishCommand(ownership, ExecutionStatus.FINISHED,
				new ExecutionCounts(2, 2, 0, 0), ConversionMessages.completed(0, 0, 0, "done"));
	}

	/**
	 * A batch the user stopped is CANCELLED even when everything it did manage to
	 * convert worked: the history has to show it did not run to the end.
	 */
	@Test
	void recordsABatchTheUserStoppedAsCancelled() {
		ExecutionOwnership ownership = Takings.owning(8L);

		recorder.finish(ownership, new ConversionTotals(5, 2, 0, 0, 1_000, 400, 600),
				ConversionMessages.completed(0, 0, 0, "cancelled"), true);

		verify(executionProgressService).finishCommand(ownership, ExecutionStatus.CANCELLED,
				new ExecutionCounts(5, 2, 0, 0), ConversionMessages.completed(0, 0, 0, "cancelled"));
	}
}