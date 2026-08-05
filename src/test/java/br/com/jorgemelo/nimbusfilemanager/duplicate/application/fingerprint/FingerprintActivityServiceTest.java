package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;

/**
 * Hashing thousands of files used to be invisible: no execution record, no
 * banner, nothing but a machine that had become slow for no stated reason.
 */
class FingerprintActivityServiceTest {

	private final PhashBacklogService photoBacklog = mock(PhashBacklogService.class);
	private final VideoFingerprintBacklogService videoBacklog = mock(VideoFingerprintBacklogService.class);
	private final FingerprintRunReader fingerprintRunReader = mock(FingerprintRunReader.class);

	private final FingerprintActivityService service = new FingerprintActivityService(photoBacklog, videoBacklog,
			fingerprintRunReader);

	@Test
	void saysNothingWhileNeitherBacklogIsWorking() {
		when(fingerprintRunReader.isRunning(ExecutionType.FINGERPRINT_VIDEO)).thenReturn(false);
		when(fingerprintRunReader.isRunning(ExecutionType.FINGERPRINT_PHOTO)).thenReturn(false);

		Assertions.assertThat(service.current()).isEmpty();
	}

	/**
	 * The count is what the whole backlog has finished, not what this run did: a
	 * run that had just started reported "0 of 6342" beside its own 96% bar.
	 */
	@Test
	void reportsTheVideoBacklogWithTheProgressOfTheWholeQueue() {
		when(fingerprintRunReader.isRunning(ExecutionType.FINGERPRINT_VIDEO)).thenReturn(true);
		when(videoBacklog.status()).thenReturn(new FingerprintBacklogStatus(269, 6069, 4));
		when(fingerprintRunReader.etaSeconds(ExecutionType.FINGERPRINT_VIDEO)).thenReturn(900L);

		Assertions.assertThat(service.current()).hasValueSatisfying(job -> {
			Assertions.assertThat(job.label()).contains("vídeo");
			Assertions.assertThat(job.processed()).isEqualTo(6073L);
			Assertions.assertThat(job.total()).isEqualTo(6342L);
			Assertions.assertThat(job.percent()).isEqualTo(95.76);
			Assertions.assertThat(job.etaSeconds()).isEqualTo(900L);
			Assertions.assertThat(job.link()).isEqualTo("/app/duplicates");
		});
	}

	@Test
	void reportsThePhotoBacklogWhenOnlyItIsWorking() {
		when(fingerprintRunReader.isRunning(ExecutionType.FINGERPRINT_VIDEO)).thenReturn(false);
		when(fingerprintRunReader.isRunning(ExecutionType.FINGERPRINT_PHOTO)).thenReturn(true);
		when(photoBacklog.status()).thenReturn(new FingerprintBacklogStatus(60, 40, 0));
		when(fingerprintRunReader.etaSeconds(ExecutionType.FINGERPRINT_PHOTO)).thenReturn(30L);

		Assertions.assertThat(service.current())
				.hasValueSatisfying(job -> Assertions.assertThat(job.label()).contains("fotos"));
	}

	/**
	 * Video first when both run: it is the one that costs ffmpeg processes and
	 * competes with a conversion, so it is the one worth naming.
	 */
	@Test
	void prefersTheVideoBacklogWhenBothAreWorking() {
		when(fingerprintRunReader.isRunning(ExecutionType.FINGERPRINT_VIDEO)).thenReturn(true);
		when(videoBacklog.status()).thenReturn(new FingerprintBacklogStatus(10, 0, 0));

		Assertions.assertThat(service.current())
				.hasValueSatisfying(job -> Assertions.assertThat(job.label()).contains("vídeo"));
	}
}