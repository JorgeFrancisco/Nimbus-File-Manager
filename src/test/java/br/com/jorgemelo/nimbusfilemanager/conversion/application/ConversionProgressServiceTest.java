package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.EtaState;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Progress;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionProgress;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionOutcome;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.model.ConversionItemResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.ConversionItemResultRepository;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * What the conversion screen reads once the encoding happens somewhere else.
 *
 * <p>
 * Everything here used to be counters in the object doing the work, which
 * answered only for the process holding them. These tests are about the same
 * numbers coming back from the row and from the lines the batch wrote as it
 * went.
 */
class ConversionProgressServiceTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-12T12:10:00Z"), ZoneOffset.UTC);

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final ConversionItemResultRepository conversionItemResultRepository = mock(
			ConversionItemResultRepository.class);

	@Test
	void reportsNothingRunningWhenNoBatchHasEverBeenAskedFor() {
		when(executionRepository.findFirstByExecutionTypeOrderByCreatedAtDesc(ExecutionType.CONVERSION))
				.thenReturn(Optional.empty());

		ConversionProgress progress = service().snapshot();

		Assertions.assertThat(progress.running()).isFalse();
		Assertions.assertThat(progress.result()).isNull();
	}

	/**
	 * Both levels of the bar: how many videos are finished, and how far into the one
	 * still encoding. The second is what a batch of a single long video needs.
	 */
	@Test
	void carriesBothLevelsOfTheBarWhileABatchRuns() {
		running(3, 1, 40, "clip.mp4");

		ConversionProgress progress = service().snapshot();

		Assertions.assertThat(progress.running()).isTrue();
		Assertions.assertThat(progress.processed()).isEqualTo(1);
		Assertions.assertThat(progress.total()).isEqualTo(3);
		Assertions.assertThat(progress.filePercent()).isEqualTo(40);
		Assertions.assertThat(progress.currentFile()).isEqualTo("clip.mp4");
		Assertions.assertThat(progress.percent()).isBetween(46.0, 47.0);
		Assertions.assertThat(progress.result()).isNull();
	}

	/**
	 * Counting in hundredths is what makes a single long video advance at all: one
	 * of one, forty per cent in, is not zero.
	 */
	@Test
	void advancesForASingleVideoThatIsOnlyPartlyEncoded() {
		running(1, 0, 40, "long.mp4");

		Assertions.assertThat(service().snapshot().percent()).isBetween(39.0, 41.0);
	}

	@Test
	void estimatesTheTimeLeftFromWhatHasActuallyBeenDone() {
		running(2, 1, 0, "clip.mp4");

		Assertions.assertThat(service().snapshot().eta().remainingSeconds()).isPositive();
	}

	/**
	 * The report is rebuilt from the lines the batch wrote, and what was saved is
	 * added up from them rather than stored again - a column holding the sum of
	 * other rows is one that can disagree with them.
	 */
	@Test
	void rebuildsTheReportFromTheLinesTheBatchWrote() {
		Execution finished = Execution.builder().id(7L).executionPublicId(UUID.randomUUID())
				.executionType(ExecutionType.CONVERSION).status(ExecutionStatus.FINISHED).filesFound(2).filesMoved(1)
				.cacheHits(1).errors(0).statusMessage(StatusMessage.raw("done")).build();

		when(executionRepository.findFirstByExecutionTypeOrderByCreatedAtDesc(ExecutionType.CONVERSION))
				.thenReturn(Optional.of(finished));
		when(conversionItemResultRepository.findByExecutionIdOrderByIdAsc(7L))
				.thenReturn(List.of(item("clip.mp4", ConversionOutcome.CONVERTED, 1000L, 400L),
						item("other.mp4", ConversionOutcome.SKIPPED, 0L, 0L)));

		ConversionProgress progress = service().snapshot();

		Assertions.assertThat(progress.running()).isFalse();
		Assertions.assertThat(progress.result().items()).hasSize(2);
		Assertions.assertThat(progress.result().items().getFirst().fileName()).isEqualTo("clip.mp4");
		Assertions.assertThat(progress.result().items().getFirst().savedBytes()).isEqualTo(600L);
		Assertions.assertThat(progress.result().savedBytes()).isEqualTo(600L);
		Assertions.assertThat(progress.result().converted()).isEqualTo(1);
		Assertions.assertThat(progress.result().message()).isEqualTo("done");
	}

	/**
	 * The screen asks this before offering the button at all, and it asks the row -
	 * the object that used to answer lives in another process now.
	 */
	@Test
	void saysWhetherABatchIsGoingOnRightNow() {
		when(executionRepository.findFirstByExecutionTypeOrderByCreatedAtDesc(ExecutionType.CONVERSION))
				.thenReturn(Optional.empty());

		Assertions.assertThat(service().running()).isFalse();
		Assertions.assertThat(service().active()).isEmpty();

		running(2, 0, 0, "clip.mp4");

		Assertions.assertThat(service().running()).isTrue();
		Assertions.assertThat(service().active()).isPresent();
	}

	/** A batch that ended is not active, however recent it is. */
	@Test
	void offersNoActiveBatchOnceTheLastOneEnded() {
		finished(List.of());

		Assertions.assertThat(service().running()).isFalse();
		Assertions.assertThat(service().active()).isEmpty();
	}

	/**
	 * The bar is held below a hundred while anything is still being written:
	 * rounding once showed a full bar with the last video at 88%.
	 */
	@Test
	void neverShowsAFullBarWhileAVideoIsStillBeingWritten() {
		running(2, 1, 100, "clip.mp4");

		Assertions.assertThat(service().snapshot().percent()).isLessThan(100.0);
	}

	/** Every file decided means the bar is full, whatever the item column says. */
	@Test
	void showsAFullBarOnceEveryFileHasBeenDecided() {
		running(2, 2, 0, "clip.mp4");

		Assertions.assertThat(service().snapshot().percent()).isEqualTo(100);
	}

	/**
	 * A row claimed but not yet started has no elapsed time to divide by, so the
	 * screen is told there is no estimate rather than a made-up one.
	 */
	@Test
	void offersNoEstimateBeforeTheBatchHasStarted() {
		Execution claimed = Execution.builder().id(7L).executionType(ExecutionType.CONVERSION)
				.status(ExecutionStatus.RUNNING).totalExpected(2).filesAnalyzed(0).build();

		when(executionRepository.findFirstByExecutionTypeOrderByCreatedAtDesc(ExecutionType.CONVERSION))
				.thenReturn(Optional.of(claimed));

		ConversionProgress progress = service().snapshot();

		Assertions.assertThat(progress.eta().state()).isEqualTo(EtaState.NOT_APPLICABLE);
		Assertions.assertThat(progress.total()).isEqualTo(2);
		Assertions.assertThat(progress.currentFile()).isNull();
	}

	/**
	 * A status message that carries no arguments names no file - the screen writes
	 * its own sentence around the name and must not be handed the sentence.
	 */
	@Test
	void namesNoFileWhenTheMessageCarriesNoArgument() {
		Execution execution = Execution.builder().id(7L).executionType(ExecutionType.CONVERSION)
				.status(ExecutionStatus.RUNNING).startedAt(LocalDateTime.now(CLOCK).minusMinutes(1)).totalExpected(1)
				.filesAnalyzed(0).statusMessage(StatusMessage.raw("no arguments here")).build();

		when(executionRepository.findFirstByExecutionTypeOrderByCreatedAtDesc(ExecutionType.CONVERSION))
				.thenReturn(Optional.of(execution));

		Assertions.assertThat(service().snapshot().currentFile()).isNull();
	}

	/**
	 * A batch that converted nothing - everything skipped - saved nothing, and
	 * dividing by what it started from would be dividing by zero.
	 */
	@Test
	void reportsNoSavingForABatchThatConvertedNothing() {
		finished(List.of(item("other.mp4", ConversionOutcome.SKIPPED, 0L, 0L)));

		Assertions.assertThat(service().snapshot().result().savedPercent()).isZero();
		Assertions.assertThat(service().snapshot().result().message()).isNull();
	}

	private void finished(List<ConversionItemResult> items) {
		Execution execution = Execution.builder().id(7L).executionPublicId(UUID.randomUUID())
				.executionType(ExecutionType.CONVERSION).status(ExecutionStatus.FINISHED).filesFound(1).filesMoved(0)
				.cacheHits(1).errors(0).build();

		when(executionRepository.findFirstByExecutionTypeOrderByCreatedAtDesc(ExecutionType.CONVERSION))
				.thenReturn(Optional.of(execution));
		when(conversionItemResultRepository.findByExecutionIdOrderByIdAsc(7L)).thenReturn(items);
	}

	private void running(int total, int processed, int filePercent, String fileName) {
		Execution execution = Execution.builder().id(7L).executionType(ExecutionType.CONVERSION)
				.status(ExecutionStatus.RUNNING).startedAt(LocalDateTime.now(CLOCK).minusMinutes(5))
				.totalExpected(total).filesAnalyzed(processed).currentItemPercent(filePercent)
				// The window the estimate is measured over: five minutes of it, opened
				// when the batch began.
				.rateWindowFromAt(LocalDateTime.now(CLOCK).minusMinutes(5)).rateWindowFromDone(0)
				.statusMessage(message(fileName)).build();

		when(executionRepository.findFirstByExecutionTypeOrderByCreatedAtDesc(ExecutionType.CONVERSION))
				.thenReturn(Optional.of(execution));
	}

	private StatusMessage message(String fileName) {
		var message = ExecutionMessages.processing(fileName);

		return StatusMessage.coded(message.code(),
				new ExecutionMessageCodec(new ObjectMapper()).encode(message.args()));
	}

	private ConversionItemResult item(String fileName, ConversionOutcome outcome, long original, long converted) {
		// The name the row carries: a conversion report is read long after the run,
		// when the file may be somewhere else entirely, so the line says what was
		// converted rather than pointing at where it is now.
		return ConversionItemResult.builder().fileName(fileName).outcome(outcome).originalBytes(original)
				.convertedBytes(converted).audioFallback(false).subtitlesDropped(false).dataDropped(false)
				.originalQuarantined(false).build();
	}

	private ConversionProgressService service() {
		return new ConversionProgressService(executionRepository, conversionItemResultRepository,
				new ExecutionMessageCodec(new ObjectMapper()), Progress.estimator(CLOCK), Progress.reader());
	}
}