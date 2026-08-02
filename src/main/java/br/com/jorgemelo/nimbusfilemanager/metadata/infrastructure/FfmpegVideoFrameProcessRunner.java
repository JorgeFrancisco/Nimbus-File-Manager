package br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataConstants;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoFrameSamplingPlan;

/**
 * External-process glue for video frame sampling: a <b>single</b> ffmpeg pass
 * that selects exactly one frame at each planned timestamp, normalizes it to a
 * 32x32 grayscale sample and streams them all through {@code pipe:1}. One
 * process per video, never one per timestamp.
 *
 * <p>
 * Frame selection uses a {@code select} expression -
 * {@code gte(t,T)*lt(prev_t,T)} per target - which picks the first frame that
 * crosses each timestamp {@code T}. This is exact per-timestamp sampling, NOT
 * the fixed-frequency behavior of the {@code fps} filter (which does not honor
 * arbitrary target timestamps). {@code -fps_mode passthrough} keeps the
 * selected frames' timing intact. Isolated in its own {@code *ProcessRunner}
 * (excluded from coverage) because the spawn cannot be meaningfully
 * unit-tested; the decode-free logic (slicing, hashing) stays in
 * {@code VideoPerceptualHashService}.
 */
@Component
public class FfmpegVideoFrameProcessRunner {

	private static final long TIMEOUT_SECONDS = 120;

	public byte[] run(String ffmpeg, Path file, VideoFrameSamplingPlan plan) throws IOException, InterruptedException {
		// The select expression escapes its inner commas (\,) so the filtergraph does
		// not split on them; no surrounding quotes are used, which the Windows process
		// argument parser would pass through literally and break the expression.
		String filter = "select=" + selectExpression(plan) + ",scale=" + MetadataConstants.SAMPLE_SIDE + ":"
				+ MetadataConstants.SAMPLE_SIDE + ":flags=lanczos,format=gray";

		List<String> command = List.of(ffmpeg, "-v", "error", "-y", "-i", file.toAbsolutePath().normalize().toString(),
				"-vf", filter, "-fps_mode", "passthrough", "-frames:v", String.valueOf(plan.frameCount()), "-f",
				"rawvideo", "-pix_fmt", "gray", "pipe:1");

		Process process = ExternalToolProcess.start(new ProcessBuilder(command), ffmpeg);

		Thread stderrDrain = drainAsync(process.getErrorStream());

		byte[] frames;

		try (InputStream stdout = process.getInputStream()) {
			frames = stdout.readAllBytes();
		}

		if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
			process.destroyForcibly();

			stderrDrain.interrupt();

			throw new IOException("FFmpeg timed out while sampling video frames");
		}

		stderrDrain.join(TimeUnit.SECONDS.toMillis(5));

		if (process.exitValue() != 0) {
			throw new IOException("FFmpeg could not sample video frames (exit " + process.exitValue() + ")");
		}

		return frames;
	}

	/**
	 * {@code gte(t,T)*lt(prev_t,T)} per target timestamp, summed - true exactly on
	 * the first frame that reaches each {@code T}, so one frame is selected per
	 * planned position.
	 */
	private String selectExpression(VideoFrameSamplingPlan plan) {
		StringJoiner expression = new StringJoiner("+");

		for (long positionMs : plan.positionsMs()) {
			String seconds = String.format(Locale.ROOT, "%.3f", positionMs / 1000.0);

			expression.add("gte(t\\," + seconds + ")*lt(prev_t\\," + seconds + ")");
		}

		return expression.toString();
	}

	private Thread drainAsync(InputStream stream) {
		Thread thread = new Thread(() -> {
			try (InputStream drained = stream; ByteArrayOutputStream sink = new ByteArrayOutputStream()) {
				drained.transferTo(sink);
			} catch (IOException _) {
				// The process is exiting; a broken drain pipe is not actionable.
			}
		});

		thread.setDaemon(true);
		thread.start();

		return thread;
	}
}