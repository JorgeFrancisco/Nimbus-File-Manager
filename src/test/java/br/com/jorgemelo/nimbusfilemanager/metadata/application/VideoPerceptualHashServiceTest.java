package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoPerceptualFingerprint;
import br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.FfmpegVideoFrameRunner;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolPaths;

class VideoPerceptualHashServiceTest {

	@TempDir
	Path tempDir;

	private VideoPerceptualHashService service(FfmpegVideoFrameRunner runner) {
		ExternalToolPaths externalToolPaths = mock(ExternalToolPaths.class);

		when(externalToolPaths.ffmpeg()).thenReturn("ffmpeg");

		return new VideoPerceptualHashService(externalToolPaths, new VideoFrameSampler(), runner);
	}

	private Path videoFile() throws Exception {
		Path file = tempDir.resolve("clip.mp4");

		Files.write(file, new byte[] { 1, 2, 3 });

		return file;
	}

	@Test
	void computeProducesOneFingerprintPerSampledFrame() throws Exception {
		VideoPerceptualHashService service = service((_, _, _) -> new byte[5 * 1024]);

		VideoPerceptualFingerprint fingerprint = service.compute(videoFile(), 10.0, 5);

		assertThat(fingerprint.frames()).hasSize(5);
		assertThat(fingerprint.frames()).extracting("sampleIndex").containsExactly(0, 1, 2, 3, 4);
		assertThat(fingerprint.frames().getFirst().positionMs()).isEqualTo(1000L);
		assertThat(fingerprint.frames().getLast().positionMs()).isEqualTo(9000L);
		assertThat(fingerprint.frames().getFirst().hash()).hasSize(32);
		assertThat(fingerprint.frames().getFirst().luminance()).hasSize(1024);
	}

	@Test
	void computeKeepsOnlyTheFramesFfmpegActuallyReturned() throws Exception {
		// ffmpeg decoded fewer frames than planned (a very short clip): keep what came.
		VideoPerceptualHashService service = service((_, _, _) -> new byte[3 * 1024]);

		VideoPerceptualFingerprint fingerprint = service.compute(videoFile(), 10.0, 5);

		assertThat(fingerprint.frames()).hasSize(3);
	}

	@Test
	void rejectsVideoWithoutADuration() throws Exception {
		VideoPerceptualHashService service = service((_, _, _) -> new byte[5 * 1024]);

		Path file = videoFile();

		assertThatThrownBy(() -> service.compute(file, null, 5))
				.isInstanceOf(UnsupportedVideoFingerprintException.class);
		assertThatThrownBy(() -> service.compute(file, 0.0, 5))
				.isInstanceOf(UnsupportedVideoFingerprintException.class);
	}

	@Test
	void rejectsWhenFfmpegReturnsNoFrames() throws Exception {
		VideoPerceptualHashService service = service((_, _, _) -> new byte[0]);

		Path file = videoFile();

		assertThatThrownBy(() -> service.compute(file, 10.0, 5))
				.isInstanceOf(UnsupportedVideoFingerprintException.class);
	}

	@Test
	void rejectsWhenFfmpegReturnsAPartialFrame() throws Exception {
		VideoPerceptualHashService service = service((_, _, _) -> new byte[1500]);

		Path file = videoFile();

		assertThatThrownBy(() -> service.compute(file, 10.0, 5))
				.isInstanceOf(UnsupportedVideoFingerprintException.class);
	}

	@Test
	void wrapsFfmpegFailures() throws Exception {
		VideoPerceptualHashService service = service((_, _, _) -> {
			throw new IllegalStateException("boom");
		});

		Path file = videoFile();

		assertThatThrownBy(() -> service.compute(file, 10.0, 5)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Could not run ffmpeg");
	}
}