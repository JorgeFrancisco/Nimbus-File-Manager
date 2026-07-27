package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;

/**
 * Hashing thousands of files used to be invisible: no execution record, no
 * banner, nothing but a machine that had become slow for no stated reason.
 */
class FingerprintActivityServiceTest {

	private final PhashBacklogAsyncRunner photoBacklogRunner = mock(PhashBacklogAsyncRunner.class);
	private final VideoFingerprintBacklogAsyncRunner videoBacklogRunner = mock(
			VideoFingerprintBacklogAsyncRunner.class);
	private final FingerprintActivityService service = new FingerprintActivityService(photoBacklogRunner,
			videoBacklogRunner);

	@Test
	void saysNothingWhileNeitherBacklogIsWorking() {
		when(videoBacklogRunner.isRunning()).thenReturn(false);
		when(photoBacklogRunner.isRunning()).thenReturn(false);

		Assertions.assertThat(service.current()).isEmpty();
	}

	/**
	 * The count is what the whole backlog has finished, not what this run did: a
	 * run that had just started reported "0 of 6342" beside its own 96% bar.
	 */
	@Test
	void reportsTheVideoBacklogWithTheProgressOfTheWholeQueue() {
		when(videoBacklogRunner.isRunning()).thenReturn(true);
		when(videoBacklogRunner.processed()).thenReturn(0L);
		when(videoBacklogRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(269, 6069, 4));
		when(videoBacklogRunner.etaSeconds()).thenReturn(900L);

		Assertions.assertThat(service.current()).hasValueSatisfying(job -> {
			Assertions.assertThat(job.label()).contains("vídeo");
			Assertions.assertThat(job.processed()).isEqualTo(6073L);
			Assertions.assertThat(job.total()).isEqualTo(6342L);
			Assertions.assertThat(job.percent()).isEqualTo(96);
			Assertions.assertThat(job.etaSeconds()).isEqualTo(900L);
			Assertions.assertThat(job.link()).isEqualTo("/app/duplicates");
		});
	}

	@Test
	void reportsThePhotoBacklogWhenOnlyItIsWorking() {
		when(videoBacklogRunner.isRunning()).thenReturn(false);
		when(photoBacklogRunner.isRunning()).thenReturn(true);
		when(photoBacklogRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(60, 40, 0));
		when(photoBacklogRunner.etaSeconds()).thenReturn(30L);

		Assertions.assertThat(service.current())
				.hasValueSatisfying(job -> Assertions.assertThat(job.label()).contains("fotos"));
	}

	/**
	 * Video first when both run: it is the one that costs ffmpeg processes and
	 * competes with a conversion, so it is the one worth naming.
	 */
	@Test
	void prefersTheVideoBacklogWhenBothAreWorking() {
		when(videoBacklogRunner.isRunning()).thenReturn(true);
		when(videoBacklogRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(10, 0, 0));

		Assertions.assertThat(service.current())
				.hasValueSatisfying(job -> Assertions.assertThat(job.label()).contains("vídeo"));
	}
}