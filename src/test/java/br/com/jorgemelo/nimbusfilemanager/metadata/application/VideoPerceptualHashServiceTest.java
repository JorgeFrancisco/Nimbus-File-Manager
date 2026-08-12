package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataConstants;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoFrameFingerprint;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoPerceptualFingerprint;
import br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.FfmpegVideoFrameRunner;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolPaths;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionMetricsContext;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ProcessingMetrics;

class VideoPerceptualHashServiceTest {

	/** This test's own accumulator: nothing here is shared with another run. */
	private final ProcessingMetrics metrics = new ExecutionMetricsContext().processing();

	@TempDir
	Path tempDir;

	/**
	 * A video of a single frame - the clip a phone keeps beside a photo, or a
	 * recording stopped the instant it started - holds nothing at the sampled
	 * timestamps. Its one frame is still a picture, and hashing it is what the
	 * photos do.
	 */
	@Test
	void computeFallsBackToTheOnlyFrameWhenSamplingFindsNone() throws Exception {
		Path file = videoFile();

		VideoPerceptualFingerprint fingerprint = service((_, _, _, _) -> new byte[0], photoService(gradient()))
				.compute(file, 0.04, 5, metrics);

		assertThat(fingerprint.frames()).singleElement().satisfies(frame -> {
			assertThat(frame.sampleIndex()).isZero();
			assertThat(frame.positionMs()).isZero();
			assertThat(frame.hash()).hasSize(32);
		});
	}

	/**
	 * An ffmpeg that never started leaves this method whole, instead of being
	 * folded into the generic sampling failure that a broken stream also produces.
	 * Downstream it is the only thing that keeps a healthy video from being written
	 * off permanently for a path that pointed at the wrong folder.
	 */
	@Test
	void letsAToolThatNeverStartedThroughUnwrapped() throws Exception {
		Path file = videoFile();

		VideoPerceptualHashService service = service((_, _, _, _) -> {
			throw new ExternalToolNotRunnableException("./tools/bin/ffmpeg.exe", new IOException("error=2"));
		}, photoService(gradient()));

		assertThatThrownBy(() -> service.compute(file, 0.04, 5, metrics))
				.isInstanceOf(ExternalToolNotRunnableException.class).hasMessageContaining("./tools/bin/ffmpeg.exe");
	}

	/** With no frame anywhere, the file is refused, not half-hashed. */
	@Test
	void computeStillRefusesAVideoWithNoFrameAtAll() throws Exception {
		Path file = videoFile();

		VideoPerceptualHashService service = service((_, _, _, _) -> new byte[0], photoService(new byte[0]));

		assertThatThrownBy(() -> service.compute(file, 0.04, 5, metrics))
				.isInstanceOf(UnsupportedVideoFingerprintException.class)
				.hasMessageContaining("single frame that could not be decoded");
	}

	/** Half a frame is not a frame: the size has to divide into whole samples. */
	@Test
	void rejectsFramesOfUnexpectedSize() throws Exception {
		VideoPerceptualHashService service = service((_, _, _, _) -> new byte[1500]);

		Path file = videoFile();

		assertThatThrownBy(() -> service.compute(file, 10.0, 5, metrics))
				.isInstanceOf(UnsupportedVideoFingerprintException.class).hasMessageContaining("unexpected size");
	}

	private byte[] gradient() {
		byte[] pixels = new byte[MetadataConstants.SAMPLE_BYTES];

		for (int index = 0; index < pixels.length; index++) {
			pixels[index] = (byte) (index % 251);
		}

		return pixels;
	}

	/**
	 * Every planned position now costs a process of its own, so one of them coming
	 * back empty leaves a set that is shorter but perfectly well formed - and
	 * indistinguishable, downstream, from the fingerprint of a shorter video. A
	 * fingerprint missing a frame is a different fingerprint, not a smaller one,
	 * and refusing it is what keeps a partial set from being stored as whole.
	 *
	 * <p>
	 * This replaces a test that asserted the opposite - keep whatever ffmpeg
	 * returned - and the reversal is not a change of mind about strictness. Under
	 * the sequential decode a short clip genuinely produced fewer frames than
	 * positions asked for, because the selection ran over the frames that existed;
	 * keeping them was right. A seek asks each position on its own and answers with
	 * the frame there, so a short clip returns five frames (some of them the same
	 * one) and never four. Measured over 55 real videos, sampling returned either
	 * all five or - for a file with no usable duration, which the fallback above
	 * takes - none. A partial set is therefore no longer a short video; it is a
	 * failure.
	 */
	@Test
	void refusesASetThatIsMissingAnyOfThePlannedFrames() throws Exception {
		Path file = videoFile();

		VideoPerceptualHashService service = service((_, _, _, _) -> frames(4));

		assertThatThrownBy(() -> service.compute(file, 60.0, 5, metrics))
				.isInstanceOf(UnsupportedVideoFingerprintException.class).hasMessageContaining("4 of the 5 frames");
	}

	/** The whole set is what a fingerprint is, and it comes back in full. */
	@Test
	void acceptsExactlyThePlannedFrames() throws Exception {
		Path file = videoFile();

		VideoPerceptualFingerprint fingerprint = service((_, _, _, _) -> frames(5)).compute(file, 60.0, 5, metrics);

		assertThat(fingerprint.frames()).hasSize(5);
	}

	/**
	 * The positions are the algorithm's identity as much as the hash is: five
	 * frames at 10, 30, 50, 70 and 90 percent of the running time. Seeking changed
	 * how they are reached, never which ones.
	 */
	@Test
	void samplesTheSameRelativePositionsItAlwaysDid() throws Exception {
		Path file = videoFile();

		VideoPerceptualFingerprint fingerprint = service((_, _, _, _) -> frames(5)).compute(file, 100.0, 5, metrics);

		assertThat(fingerprint.frames()).extracting(VideoFrameFingerprint::positionMs)
				.containsExactly(10_000L, 30_000L, 50_000L, 70_000L, 90_000L);
	}

	/**
	 * The same file sampled twice gives the same answer, which is what lets a
	 * rebuild be compared against what it replaced. An input seek is deterministic
	 * for a given file: it lands on the frame at the timestamp, not wherever the
	 * decoder happened to be.
	 */
	@Test
	void producesTheSameFingerprintForTheSameFile() throws Exception {
		Path file = videoFile();

		VideoPerceptualHashService service = service((_, _, _, _) -> frames(5));

		VideoPerceptualFingerprint first = service.compute(file, 60.0, 5, metrics);
		VideoPerceptualFingerprint second = service.compute(file, 60.0, 5, metrics);

		assertThat(first.frames()).usingRecursiveComparison().isEqualTo(second.frames());
	}

	/** Distinguishable samples, so a wrong slice cannot pass as the right one. */
	private byte[] frames(int count) {
		byte[] frames = new byte[count * MetadataConstants.SAMPLE_BYTES];

		for (int frame = 0; frame < count; frame++) {
			Arrays.fill(frames, frame * MetadataConstants.SAMPLE_BYTES,
					(frame + 1) * MetadataConstants.SAMPLE_BYTES, (byte) (frame + 1));
		}

		return frames;
	}

	private VideoPerceptualHashService service(FfmpegVideoFrameRunner runner) {
		return service(runner, photoService(new byte[0]));
	}

	/**
	 * The fallback reads the only frame through the photo service, so a stub of it
	 * is what decides whether a frame comes back at all.
	 */
	private PhotoPerceptualHashService photoService(byte[] sample) {
		ExternalToolPaths paths = mock(ExternalToolPaths.class);

		when(paths.ffmpeg()).thenReturn("ffmpeg");

		return new PhotoPerceptualHashService(paths, (_, _, _) -> sample, (_, _, _) -> {
			throw new UnsupportedOperationException("A video frame is never read as part of a group");
		}, mock(WorkspaceManager.class));
	}

	private VideoPerceptualHashService service(FfmpegVideoFrameRunner runner,
			PhotoPerceptualHashService photoPerceptualHashService) {
		ExternalToolPaths externalToolPaths = mock(ExternalToolPaths.class);

		when(externalToolPaths.ffmpeg()).thenReturn("ffmpeg");

		return new VideoPerceptualHashService(externalToolPaths, new VideoFrameSampler(), runner,
				photoPerceptualHashService);
	}

	private Path videoFile() throws Exception {
		Path file = tempDir.resolve("clip.mp4");

		Files.write(file, new byte[] { 1, 2, 3 });

		return file;
	}

	@Test
	void computeProducesOneFingerprintPerSampledFrame() throws Exception {
		VideoPerceptualHashService service = service((_, _, _, _) -> new byte[5 * 1024]);

		VideoPerceptualFingerprint fingerprint = service.compute(videoFile(), 10.0, 5, metrics);

		assertThat(fingerprint.frames()).hasSize(5);
		assertThat(fingerprint.frames()).extracting("sampleIndex").containsExactly(0, 1, 2, 3, 4);
		assertThat(fingerprint.frames().getFirst().positionMs()).isEqualTo(1000L);
		assertThat(fingerprint.frames().getLast().positionMs()).isEqualTo(9000L);
		assertThat(fingerprint.frames().getFirst().hash()).hasSize(32);
		assertThat(fingerprint.frames().getFirst().luminance()).hasSize(1024);
	}

	@Test
	void rejectsVideoWithoutADuration() throws Exception {
		VideoPerceptualHashService service = service((_, _, _, _) -> new byte[5 * 1024]);

		Path file = videoFile();

		assertThatThrownBy(() -> service.compute(file, null, 5, metrics))
				.isInstanceOf(UnsupportedVideoFingerprintException.class);
		assertThatThrownBy(() -> service.compute(file, 0.0, 5, metrics))
				.isInstanceOf(UnsupportedVideoFingerprintException.class);
	}

	@Test
	void rejectsWhenFfmpegReturnsNoFrames() throws Exception {
		VideoPerceptualHashService service = service((_, _, _, _) -> new byte[0]);

		Path file = videoFile();

		assertThatThrownBy(() -> service.compute(file, 10.0, 5, metrics))
				.isInstanceOf(UnsupportedVideoFingerprintException.class);
	}

	@Test
	void rejectsWhenFfmpegReturnsAPartialFrame() throws Exception {
		VideoPerceptualHashService service = service((_, _, _, _) -> new byte[1500]);

		Path file = videoFile();

		assertThatThrownBy(() -> service.compute(file, 10.0, 5, metrics))
				.isInstanceOf(UnsupportedVideoFingerprintException.class);
	}

	@Test
	void wrapsFfmpegFailures() throws Exception {
		VideoPerceptualHashService service = service((_, _, _, _) -> {
			throw new IllegalStateException("boom");
		});

		Path file = videoFile();

		assertThatThrownBy(() -> service.compute(file, 10.0, 5, metrics)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Could not run ffmpeg");
	}
}