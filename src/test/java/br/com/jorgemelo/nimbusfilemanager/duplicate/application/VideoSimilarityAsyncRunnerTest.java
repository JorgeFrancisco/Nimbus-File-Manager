package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class VideoSimilarityAsyncRunnerTest {

	private final VideoSimilarityService service = mock(VideoSimilarityService.class);
	private final VideoSimilarityAsyncRunner runner = new VideoSimilarityAsyncRunner(service);

	@Test
	void doesNotClaimWorkWhenAlreadyCached() {
		when(service.isCached(80)).thenReturn(true);

		assertThat(runner.start(80)).isFalse();
	}

	@Test
	void claimsWorkAndTracksProgressWhileRunning() {
		when(service.isCached(80)).thenReturn(false);
		doAnswer(invocation -> {
			invocation.<SimilarityProgressCallback>getArgument(1).update(5, 10);

			return null;
		}).when(service).computeAndCache(eq(80), any());

		assertThat(runner.start(80)).isTrue();

		runner.run(80);

		assertThat(runner.processed()).isEqualTo(5);
		assertThat(runner.total()).isEqualTo(10);
		assertThat(runner.percent()).isEqualTo(50);
		assertThat(runner.isRunning()).isFalse();
	}

	@Test
	void releasesTheLockEvenWhenTheGroupingFails() {
		when(service.isCached(80)).thenReturn(false);
		doThrow(new IllegalStateException("boom")).when(service).computeAndCache(eq(80), any());

		runner.start(80);
		runner.run(80);

		assertThat(runner.isRunning()).isFalse();
	}
}