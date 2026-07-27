package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.function.BooleanSupplier;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintBacklogResumer;

class VideoConversionAsyncRunnerTest {

	private final VideoConversionService service = mock(VideoConversionService.class);
	private final FingerprintBacklogResumer backlogResumer = mock(FingerprintBacklogResumer.class);
	private final VideoConversionAsyncRunner runner = new VideoConversionAsyncRunner(service, backlogResumer);

	private final List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());

	@Test
	void claimsWorkFollowsBothProgressDimensionsAndKeepsTheResult() {
		ConversionResult result = ConversionResult.empty("done");

		doAnswer(invocation -> {
			ConversionProgressCallback callback = invocation.getArgument(2);

			callback.update(0, 2, 40, "clip.mp4");
			callback.update(2, 2, 100, "clip.mp4");

			return result;
		}).when(service).convert(any(), any(), any(), any());

		Assertions.assertThat(runner.start(2)).isTrue();

		runner.run(ids, ConversionOptions.defaults());

		Assertions.assertThat(runner.processed()).isEqualTo(2);
		Assertions.assertThat(runner.total()).isEqualTo(2);
		Assertions.assertThat(runner.filePercent()).isEqualTo(100);
		Assertions.assertThat(runner.currentFile()).isEqualTo("clip.mp4");
		Assertions.assertThat(runner.percent()).isEqualTo(100);
		Assertions.assertThat(runner.isRunning()).isFalse();
		Assertions.assertThat(runner.lastResult()).isSameAs(result);
	}

	@Test
	void countsTheCurrentFileSoASingleLongVideoStillAdvances() {
		doAnswer(invocation -> {
			ConversionProgressCallback callback = invocation.getArgument(2);

			callback.update(0, 2, 50, "clip.mp4");

			return null;
		}).when(service).convert(any(), any(), any(), any());

		runner.start(2);
		runner.run(ids, ConversionOptions.defaults());

		// Half of the first of two files is a quarter of the batch.
		Assertions.assertThat(runner.percent()).isEqualTo(25);
	}

	/**
	 * The bar reaches 100% when the batch is done, never while the encoder is still
	 * writing the last file. Rounding used to close it early - the bigger the
	 * batch, the earlier: with a hundred files, the last one crossing half way was
	 * enough.
	 */
	@Test
	void holdsTheBarBelowFullWhileTheLastFileIsStillBeingWritten() {
		doAnswer(invocation -> {
			ConversionProgressCallback callback = invocation.getArgument(2);

			callback.update(99, 100, 90, "last.mp4");

			return null;
		}).when(service).convert(any(), any(), any(), any());

		runner.start(100);
		runner.run(ids, ConversionOptions.defaults());

		Assertions.assertThat(runner.percent()).isEqualTo(99);
	}

	@Test
	void doesNotClaimASecondBatchWhileOneIsRunning() {
		Assertions.assertThat(runner.start(3)).isTrue();
		Assertions.assertThat(runner.start(3)).isFalse();

		verify(service, never()).convert(any(), any(), any(), any());
	}

	@Test
	void reportsNoProgressBeforeAnythingIsClaimed() {
		Assertions.assertThat(runner.percent()).isZero();
		Assertions.assertThat(runner.isRunning()).isFalse();
		Assertions.assertThat(runner.lastResult()).isNull();
	}

	@Test
	void releasesTheClaimAndReportsAFailureWhenTheBatchBlowsUp() {
		doThrow(new IllegalStateException("boom")).when(service).convert(any(), any(), any(), any());

		runner.start(1);
		runner.run(ids, ConversionOptions.defaults());

		Assertions.assertThat(runner.isRunning()).isFalse();
		Assertions.assertThat(runner.lastResult().message()).isNotBlank();
	}

	/**
	 * The fingerprint backlogs step aside while a conversion runs and have nobody
	 * to restart them - the batch is the only one that knows it is over. Without
	 * this they would stay idle until the next restart.
	 */
	@Test
	void resumesTheFingerprintBacklogsWhenTheBatchEnds() {
		runner.start(1);
		runner.run(ids, ConversionOptions.defaults());

		verify(backlogResumer).resume();
	}

	/** Including when the batch failed: the backlog is not to blame for that. */
	@Test
	void resumesTheFingerprintBacklogsEvenWhenTheBatchBlowsUp() {
		doThrow(new IllegalStateException("boom")).when(service).convert(any(), any(), any(), any());

		runner.start(1);
		runner.run(ids, ConversionOptions.defaults());

		verify(backlogResumer).resume();
	}

	@Test
	void stopsTheBatchWhenTheUserCancelsIt() {
		doAnswer(invocation -> {
			BooleanSupplier cancelled = invocation.getArgument(3);

			Assertions.assertThat(cancelled.getAsBoolean()).isFalse();

			runner.cancel();

			Assertions.assertThat(cancelled.getAsBoolean()).isTrue();

			return ConversionResult.empty("cancelled");
		}).when(service).convert(any(), any(), any(), any());

		runner.start(2);
		runner.run(ids, ConversionOptions.defaults());

		Assertions.assertThat(runner.isRunning()).isFalse();
	}

	@Test
	void refusesToCancelWhenNothingIsRunning() {
		Assertions.assertThat(runner.cancel()).isFalse();
		Assertions.assertThat(runner.isCancelled()).isFalse();
	}

	@Test
	void forgetsAPreviousCancellationWhenANewBatchIsClaimed() {
		runner.start(1);
		runner.cancel();

		Assertions.assertThat(runner.isCancelled()).isTrue();

		runner.run(ids, ConversionOptions.defaults());

		Assertions.assertThat(runner.start(1)).isTrue();
		Assertions.assertThat(runner.isCancelled()).isFalse();
	}

	@Test
	void reportsNoEstimateBeforeAnythingIsRunning() {
		Assertions.assertThat(runner.etaSeconds()).isEqualTo(-1);
	}

	@Test
	void offersNoEstimateUntilEnoughOfTheBatchHasElapsed() {
		doAnswer(invocation -> {
			ConversionProgressCallback callback = invocation.getArgument(2);

			callback.update(1, 2, 0, "clip.mp4");

			// Half of a two-file batch, but only milliseconds in: an estimate drawn from
			// that would be noise, so the screen is told "unknown" instead.
			Assertions.assertThat(runner.etaSeconds()).isEqualTo(-1);

			return ConversionResult.empty("done");
		}).when(service).convert(any(), any(), any(), any());

		runner.start(2);
		runner.run(ids, ConversionOptions.defaults());

		// And the estimate only makes sense while something is running.
		Assertions.assertThat(runner.etaSeconds()).isEqualTo(-1);
	}

	@Test
	void releasesTheClaimWhenTheAsyncTaskWasNeverSubmitted() {
		runner.start(2);

		runner.releaseRejectedSubmission();

		Assertions.assertThat(runner.isRunning()).isFalse();
		Assertions.assertThat(runner.lastResult()).isNotNull();
		Assertions.assertThat(runner.start(2)).isTrue();
	}
}