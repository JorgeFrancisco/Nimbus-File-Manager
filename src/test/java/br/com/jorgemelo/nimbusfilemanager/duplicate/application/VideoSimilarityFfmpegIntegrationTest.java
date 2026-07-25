package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoFrameHash;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoSignature;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.VideoFrameSampler;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.VideoPerceptualHashService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoPerceptualFingerprint;
import br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.FfmpegVideoFrameProcessRunner;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ExternalToolGate;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingMetrics;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.VideoSimilarityProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Tools;

/**
 * End-to-end validation of video similarity against the real ffmpeg binary: it
 * generates synthetic clips, runs the actual frame extraction + pHash pipeline
 * and asserts the robustness targets. Runs only where the bundled Windows ffmpeg
 * is present (like every other external-process glue, it is skipped on the Linux
 * CI, which has no {@code tools/bin/ffmpeg.exe}); this is the manual/local
 * verification the {@code *ProcessRunner} coverage exclusion refers to.
 *
 * <p>
 * It also empirically confirms exact per-timestamp sampling: a 10s clip yields
 * frames at 10/30/50/70/90%.
 */
@EnabledIf("ffmpegAvailable")
class VideoSimilarityFfmpegIntegrationTest {

	private static final Path FFMPEG = Path.of("tools", "bin", "ffmpeg.exe");
	private static final int THRESHOLD = 70;

	@TempDir
	Path tempDir;

	static boolean ffmpegAvailable() {
		return Files.exists(FFMPEG);
	}

	private VideoPerceptualHashService hashService() {
		NimbusFileManagerProperties properties = mock(NimbusFileManagerProperties.class);
		Tools tools = mock(Tools.class);
		AppSettingService appSettingService = mock(AppSettingService.class);

		when(properties.tools()).thenReturn(tools);
		when(tools.ffmpeg()).thenReturn(FFMPEG.toString());
		when(appSettingService.stringValue(any(), any())).thenReturn(FFMPEG.toString());

		ExternalToolGate gate = new ExternalToolGate(new ProcessingProperties(2, 8, 2, 2, 2), new ProcessingMetrics());

		return new VideoPerceptualHashService(properties, appSettingService, new VideoFrameSampler(), gate,
				new FfmpegVideoFrameProcessRunner());
	}

	private VideoSimilarityAlgorithm algorithm() {
		return new FfmpegLanczosFramesPhashAlgorithm(hashService(), new PhotoSsimService(),
				new VideoSimilarityProperties(null, null, null, null, null, null));
	}

	@Test
	void samplesExactlyFiveFramesAtTenThirtyFiftySeventyNinetyPercent() throws Exception {
		Path clip = run("base.mp4", "-f", "lavfi", "-i", "testsrc2=size=640x480:rate=30:duration=10", "-c:v",
				"libx264", "-crf", "18", "-pix_fmt", "yuv420p");

		VideoPerceptualFingerprint fingerprint = hashService().compute(clip, 10.0,
				FfmpegLanczosFramesPhashAlgorithm.FRAME_SAMPLES);

		assertThat(fingerprint.frames()).hasSize(5);
		assertThat(fingerprint.frames()).extracting("positionMs").containsExactly(1000L, 3000L, 5000L, 7000L, 9000L);
	}

	@Test
	void sameVideoRecodedToADifferentCodecStillMatches() throws Exception {
		Path base = base();
		Path recoded = run("recoded.mp4", "-i", base.toString(), "-c:v", "libx265", "-crf", "30", "-pix_fmt",
				"yuv420p");

		assertThat(similarity(base, 10.0, 640, 480, recoded, 10.0, 640, 480)).isGreaterThanOrEqualTo(THRESHOLD);
	}

	@Test
	void differentResolutionOfTheSameVideoMatches() throws Exception {
		Path base = base();
		Path lowRes = run("lowres.mp4", "-i", base.toString(), "-vf", "scale=320:240", "-c:v", "libx264", "-crf", "23");

		assertThat(similarity(base, 10.0, 640, 480, lowRes, 10.0, 320, 240)).isGreaterThanOrEqualTo(THRESHOLD);
	}

	@Test
	void lowerBitrateAndHeavierCompressionOfTheSameVideoMatches() throws Exception {
		Path base = base();
		Path lowBitrate = run("lowbr.mp4", "-i", base.toString(), "-c:v", "libx264", "-b:v", "80k", "-pix_fmt",
				"yuv420p");

		assertThat(similarity(base, 10.0, 640, 480, lowBitrate, 10.0, 640, 480)).isGreaterThanOrEqualTo(THRESHOLD);
	}

	@Test
	void smallDurationDifferenceOfTheSameVideoMatches() throws Exception {
		Path base = base();
		Path trimmed = run("trim.mp4", "-i", base.toString(), "-t", "9.5", "-c:v", "libx264", "-crf", "18");

		assertThat(similarity(base, 10.0, 640, 480, trimmed, 9.5, 640, 480)).isGreaterThanOrEqualTo(THRESHOLD);
	}

	@Test
	void distinctVideosWithSimilarDurationAndAspectDoNotMatch() throws Exception {
		Path base = base();
		Path distinct = run("distinct.mp4", "-f", "lavfi", "-i", "testsrc2=size=640x480:rate=30:duration=10", "-c:v",
				"libx264", "-crf", "18", "-pix_fmt", "yuv420p");

		assertThat(similarity(base, 10.0, 640, 480, distinct, 10.0, 640, 480)).isLessThan(THRESHOLD);
	}

	@Test
	void oneDivergentFrameIsToleratedByTheQuorum() throws Exception {
		Path base = base();
		// A white box covers the whole frame around t=5s, wrecking the 50% sample only.
		Path overlaid = run("overlay.mp4", "-i", base.toString(), "-vf",
				"drawbox=x=0:y=0:w=iw:h=ih:color=white@1.0:t=fill:enable='between(t,4.4,5.6)'", "-c:v", "libx264",
				"-crf", "18");

		assertThat(similarity(base, 10.0, 640, 480, overlaid, 10.0, 640, 480)).isGreaterThanOrEqualTo(THRESHOLD);
	}

	@Test
	void aCorruptedFileFailsInsteadOfProducingAFingerprint() throws Exception {
		Path corrupted = tempDir.resolve("corrupt.mp4");

		Files.write(corrupted, "this is not a video".getBytes());

		VideoSimilarityAlgorithm algorithm = algorithm();

		assertThatThrownBy(() -> algorithm.fingerprint(corrupted, 10.0)).isInstanceOf(RuntimeException.class);
	}

	/**
	 * A clip with rich, distributed structure and smooth continuous motion (a
	 * slowly zooming Mandelbrot), standing in for real footage: the structure keeps
	 * the downscaled 32x32 sample stable under resolution/compression changes, and
	 * the smooth motion keeps temporally close frames similar, so a small duration
	 * change barely moves the sampled content - unlike a rapidly changing test
	 * pattern or a near-uniform frame.
	 */
	private Path base() throws Exception {
		return run("base.mp4", "-f", "lavfi", "-i", "mandelbrot=size=640x480:rate=30", "-t", "10", "-c:v", "libx264",
				"-crf", "18", "-pix_fmt", "yuv420p");
	}

	private int similarity(Path first, double firstDuration, int firstWidth, int firstHeight, Path second,
			double secondDuration, int secondWidth, int secondHeight) {
		VideoSignature a = signatureOf(first, firstDuration, firstWidth, firstHeight);
		VideoSignature b = signatureOf(second, secondDuration, secondWidth, secondHeight);

		return algorithm().similarityPercent(a, b, THRESHOLD);
	}

	private VideoSignature signatureOf(Path video, double duration, int width, int height) {
		VideoPerceptualFingerprint fingerprint = hashService().compute(video, duration,
				FfmpegLanczosFramesPhashAlgorithm.FRAME_SAMPLES);

		List<VideoFrameHash> frames = fingerprint.frames().stream()
				.map(frame -> new VideoFrameHash(frame.sampleIndex(), frame.hash(), frame.luminance())).toList();

		return new VideoSignature(UUID.randomUUID(), frames, duration, width, height);
	}

	private Path run(String name, String... args) throws Exception {
		Path out = tempDir.resolve(name);

		List<String> command = new ArrayList<>(List.of(FFMPEG.toString(), "-v", "error", "-y"));

		command.addAll(Arrays.asList(args));
		command.add(out.toString());

		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

		String output = new String(process.getInputStream().readAllBytes());

		assertThat(process.waitFor(90, TimeUnit.SECONDS)).as("ffmpeg timed out").isTrue();
		assertThat(process.exitValue()).as("ffmpeg failed: %s", output).isZero();

		return out;
	}
}
