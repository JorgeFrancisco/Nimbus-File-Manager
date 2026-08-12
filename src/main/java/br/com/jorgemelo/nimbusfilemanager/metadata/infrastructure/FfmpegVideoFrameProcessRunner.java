package br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataConstants;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoFrameSamplingPlan;

/**
 * External-process glue for video frame sampling: one ffmpeg pass per planned
 * timestamp, each seeking straight to its own position, normalizing the frame
 * to a 32x32 grayscale sample and streaming it through {@code pipe:1}.
 *
 * <p>
 * <b>The seek is an input seek, and that is the whole point.</b> Placed before
 * {@code -i}, ffmpeg jumps to the keyframe preceding the target and decodes
 * forward only to it. Placed after, it would decode the file from the start and
 * throw away everything before the target - which is what the previous version
 * did by other means: a {@code select} expression over a sequential decode,
 * reading a whole file to keep five frames. Measured over 35 real videos, that
 * cost 310 s against 131 s here, and the difference grows with the file: a
 * 264 MB video went from 47 s to 3 s.
 *
 * <p>
 * <b>Five processes rather than one.</b> A single pass with five seeked inputs
 * was measured and rejected: it returned three frames instead of five on 33 of
 * those videos, and even when it worked it saved 11% - not a trade worth a
 * five-input filtergraph whose failure mode is silently producing the wrong
 * number of frames.
 *
 * <p>
 * What comes back is what the processes produced: a position that yielded
 * nothing contributes nothing. Whether that adds up to a whole fingerprint, to
 * the empty result a one-frame video is entitled to, or to a partial failure is
 * decided by {@code VideoPerceptualHashService}, along with the rest of the
 * decode-free logic. This class is excluded from coverage because a spawn cannot
 * be meaningfully unit-tested, and a rule nobody can test does not belong behind
 * that exclusion.
 */
@Component
public class FfmpegVideoFrameProcessRunner {

	/** Per invocation, not for the whole video: each seek is its own process. */
	private static final long TIMEOUT_SECONDS = 120;

	public byte[] run(String ffmpeg, Path file, VideoFrameSamplingPlan plan) throws IOException, InterruptedException {
		ByteArrayOutputStream frames = new ByteArrayOutputStream();

		for (long positionMs : plan.positionsMs()) {
			frames.write(frameAt(ffmpeg, file, positionMs));
		}

		return frames.toByteArray();
	}

	/**
	 * One frame, at one position. Seeking before {@code -i} is what makes this
	 * cost the same on a 4 GB file as on a 4 MB one; ffmpeg still decodes from the
	 * preceding keyframe to the requested instant, so the frame is the one at that
	 * timestamp rather than the keyframe itself.
	 */
	private byte[] frameAt(String ffmpeg, Path file, long positionMs) throws IOException, InterruptedException {
		String at = String.format(Locale.ROOT, "%.3f", positionMs / 1000.0);

		List<String> command = List.of(ffmpeg, "-v", "error", "-y", "-ss", at, "-i",
				file.toAbsolutePath().normalize().toString(), "-frames:v", "1", "-vf",
				"scale=" + MetadataConstants.SAMPLE_SIDE + ":" + MetadataConstants.SAMPLE_SIDE
						+ ":flags=lanczos,format=gray",
				"-f", "rawvideo", "-pix_fmt", "gray", "pipe:1");

		Process process = ExternalToolProcess.start(new ProcessBuilder(command), ffmpeg);

		Thread stderrDrain = drainAsync(process.getErrorStream());

		byte[] frame;

		try (InputStream stdout = process.getInputStream()) {
			frame = stdout.readAllBytes();
		}

		if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
			process.destroyForcibly();

			stderrDrain.interrupt();

			throw new IOException("FFmpeg timed out while sampling the video frame at " + positionMs + " ms");
		}

		stderrDrain.join(TimeUnit.SECONDS.toMillis(5));

		if (process.exitValue() != 0) {
			throw new IOException("FFmpeg could not sample the video frame at " + positionMs + " ms (exit "
					+ process.exitValue() + ")");
		}

		return frame;
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