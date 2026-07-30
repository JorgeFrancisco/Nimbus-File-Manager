package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.VideoSimilarityAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintFailure;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintFailureRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.FingerprintFailureDetail;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingVideo;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.UnsupportedVideoFingerprintException;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoFrameFingerprint;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoPerceptualFingerprint;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingCoordinator;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingMetrics;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;

@ExtendWith(MockitoExtension.class)
class VideoFingerprintBacklogServiceTest {

	private static final String ALGORITHM = FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1;

	@Mock
	private MediaFingerprintRepository mediaFingerprintRepository;

	@Mock
	private FingerprintFailureRepository fingerprintFailureRepository;

	@Mock
	private VideoSimilarityAlgorithm algorithm;

	@Mock
	private ExecutionQueryService executionQueryService;

	/**
	 * The Duplicados screen offers retry, listing and rebuild for videos exactly as
	 * it does for photos, and only the photo side was covered.
	 */
	@Test
	void resetFailuresClearsTheRetryableFailureRows() {
		when(fingerprintFailureRepository.deleteRetryableByKindAndAlgorithm(FingerprintKind.VIDEO_PHASH, ALGORITHM,
				FingerprintFailureReason.UNKNOWN)).thenReturn(4L);

		assertThat(service().resetFailures()).isEqualTo(4L);
	}

	@Test
	void failuresReturnsOnlyTheExhaustedRowsWithTheirPaths() {
		List<FingerprintFailureDetail> expected = List
				.of(new FingerprintFailureDetail("C:/videos/broken.mp4", FingerprintFailureReason.DECODER_REFUSED,
						"decode failed"));

		when(fingerprintFailureRepository.findExhaustedVideoWithPath(FingerprintKind.VIDEO_PHASH, ALGORITHM,
				VideoFingerprintBacklogService.MAX_ATTEMPTS)).thenReturn(expected);

		assertThat(service().failures()).isSameAs(expected);
	}

	@Test
	void rebuildDeletesOnlyThisAlgorithmsVideoFingerprintsAndFailures() {
		when(mediaFingerprintRepository.deleteByKindAndAlgorithm(FingerprintKind.VIDEO_PHASH, ALGORITHM))
				.thenReturn(12L);

		assertThat(service().rebuild()).isEqualTo(12L);

		verify(fingerprintFailureRepository).deleteByKindAndAlgorithm(FingerprintKind.VIDEO_PHASH, ALGORITHM);
		verify(mediaFingerprintRepository).deleteByKindAndAlgorithm(FingerprintKind.VIDEO_PHASH, ALGORITHM);
	}

	private VideoFingerprintBacklogService service() {
		when(algorithm.kind()).thenReturn(FingerprintKind.VIDEO_PHASH);
		when(algorithm.algorithm()).thenReturn(ALGORITHM);

		return new VideoFingerprintBacklogService(mediaFingerprintRepository, fingerprintFailureRepository, algorithm,
				new ProcessingCoordinator(new ProcessingProperties(1, 8, 1, 1, 1, 1), new ProcessingMetrics()),
				executionQueryService, mock(PlatformTransactionManager.class), Clock.systemDefaultZone());
	}

	private VideoPerceptualFingerprint fingerprint(int frames) {
		return new VideoPerceptualFingerprint(
				List.of(frame(0, 1000), frame(1, 3000), frame(2, 5000)).subList(0, frames));
	}

	private VideoFrameFingerprint frame(int index, long positionMs) {
		return new VideoFrameFingerprint(index, positionMs, new byte[32], new byte[1024]);
	}

	@SuppressWarnings("unchecked")
	@Test
	void drainStoresOneRowPerSampledFrame() {
		PendingVideo video = new PendingVideo(1L, "/tmp/clip.mp4", 10.0);

		when(mediaFingerprintRepository.findPendingVideos(eq(FingerprintKind.VIDEO_PHASH), eq(ALGORITHM), anyInt(),
				any())).thenReturn(List.of(video), List.of());
		when(algorithm.fingerprint(Path.of("/tmp/clip.mp4"), 10.0)).thenReturn(fingerprint(3));
		when(executionQueryService.active()).thenReturn(Optional.empty());

		DrainResult result = service().drainPending(() -> false, (_, _) -> {
		});

		assertThat(result.processed()).isEqualTo(1);

		ArgumentCaptor<MediaFingerprint> saved = ArgumentCaptor.forClass(MediaFingerprint.class);

		verify(mediaFingerprintRepository, times(3)).save(saved.capture());

		assertThat(saved.getAllValues()).extracting(MediaFingerprint::getSampleIndex).containsExactly(0, 1, 2);
		assertThat(saved.getAllValues()).extracting(MediaFingerprint::getPositionMs).containsExactly(1000L, 3000L,
				5000L);
		assertThat(saved.getAllValues()).allSatisfy(row -> {
			assertThat(row.getKind()).isEqualTo(FingerprintKind.VIDEO_PHASH);
			assertThat(row.getAlgorithm()).isEqualTo(ALGORITHM);
			assertThat(row.getHashBytes()).hasSize(32);
			assertThat(row.getSampleBytes()).hasSize(1024);
		});
	}

	@Test
	void statusDerivesVideoCountsFromTheTables() {
		when(mediaFingerprintRepository.countFingerprintedCatalogFiles(FingerprintKind.VIDEO_PHASH, ALGORITHM))
				.thenReturn(6L);
		when(fingerprintFailureRepository.countExhaustedVideoFailures(eq(FingerprintKind.VIDEO_PHASH), eq(ALGORITHM),
				anyInt())).thenReturn(1L);
		when(mediaFingerprintRepository.countPendingVideos(eq(FingerprintKind.VIDEO_PHASH), eq(ALGORITHM), anyInt()))
				.thenReturn(3L);

		FingerprintBacklogStatus status = service().status();

		assertThat(status.done()).isEqualTo(6);
		assertThat(status.failed()).isEqualTo(1);
		assertThat(status.pending()).isEqualTo(3);
	}

	@SuppressWarnings("unchecked")
	@Test
	void anUndecodableVideoBecomesTerminalImmediately() {
		PendingVideo video = new PendingVideo(2L, "/tmp/broken.mp4", 5.0);

		when(mediaFingerprintRepository.findPendingVideos(eq(FingerprintKind.VIDEO_PHASH), eq(ALGORITHM), anyInt(),
				any())).thenReturn(List.of(video), List.of());
		when(algorithm.fingerprint(Path.of("/tmp/broken.mp4"), 5.0))
				.thenThrow(new UnsupportedVideoFingerprintException("no frames"));
		when(fingerprintFailureRepository.findByCatalogFileIdAndKindAndAlgorithm(eq(2L), any(), any()))
				.thenReturn(Optional.empty());
		when(executionQueryService.active()).thenReturn(Optional.empty());

		service().drainPending(() -> false, (_, _) -> {
		});

		ArgumentCaptor<FingerprintFailure> failure = ArgumentCaptor.forClass(FingerprintFailure.class);

		verify(fingerprintFailureRepository).save(failure.capture());

		assertThat(failure.getValue().getAttempts()).isEqualTo(VideoFingerprintBacklogService.MAX_ATTEMPTS);
		assertThat(failure.getValue().getReason()).isEqualTo(FingerprintFailureReason.UNSUPPORTED_FORMAT);
	}

	/**
	 * An error the video pipeline did not raise itself says nothing about why the
	 * file failed, so the file is inspected to classify it - and an unreadable one
	 * stays UNKNOWN, which is retryable rather than terminal.
	 */
	@SuppressWarnings("unchecked")
	@Test
	void anUnexpectedErrorIsClassifiedByInspectingTheFile() {
		PendingVideo video = new PendingVideo(3L, "/tmp/vanished.mp4", 5.0);

		when(mediaFingerprintRepository.findPendingVideos(eq(FingerprintKind.VIDEO_PHASH), eq(ALGORITHM), anyInt(),
				any())).thenReturn(List.of(video), List.of());
		when(algorithm.fingerprint(Path.of("/tmp/vanished.mp4"), 5.0))
				.thenThrow(new IllegalStateException("ffmpeg vanished"));
		when(fingerprintFailureRepository.findByCatalogFileIdAndKindAndAlgorithm(eq(3L), any(), any()))
				.thenReturn(Optional.empty());
		when(executionQueryService.active()).thenReturn(Optional.empty());

		service().drainPending(() -> false, (_, _) -> {
		});

		ArgumentCaptor<FingerprintFailure> failure = ArgumentCaptor.forClass(FingerprintFailure.class);

		verify(fingerprintFailureRepository).save(failure.capture());

		assertThat(failure.getValue().getReason()).isEqualTo(FingerprintFailureReason.UNKNOWN);
		assertThat(failure.getValue().getAttempts()).isEqualTo(1);
	}
}