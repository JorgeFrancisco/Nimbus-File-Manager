package br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.PhotoHashFilterGraph;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.PhotoPerceptualHashService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataConstants;

/**
 * External-process glue for the photo perceptual hash: spawns ffmpeg to decode
 * and normalize the first frame to a fixed {@code SAMPLE_SIDE x SAMPLE_SIDE}
 * grayscale raw sample.
 *
 * <p>
 * Isolated in its own {@code *ProcessRunner} class (excluded from coverage in
 * pom.xml) because process spawning and stream draining cannot be meaningfully
 * unit-tested - a real ffmpeg binary and disk are required. The pure, testable
 * hash math stays in {@link PhotoPerceptualHashService}.
 */
@Component
public class FfmpegPhotoHashProcessRunner {

	private static final int TIMEOUT_SECONDS = 30;
	/**
	 * A group is given the single-file budget plus a share per photo, rather than
	 * the single-file budget multiplied: the whole point of the group is that the
	 * per-photo cost drops, and a bound that grew with it would take minutes to
	 * notice a decoder that hung on the first one.
	 */
	private static final int TIMEOUT_PER_PHOTO_SECONDS = 2;

	public byte[] run(String ffmpegPath, Path file) throws InterruptedException {
		ProcessBuilder builder = new ProcessBuilder(ffmpegPath, "-v", "error", "-y", "-i",
				file.toAbsolutePath().normalize().toString(), "-vframes", "1", "-vf", PhotoHashFilterGraph.scale(),
				"-f", "rawvideo", "-pix_fmt", "gray", "pipe:1");

		return decode(builder, ffmpegPath, "file: " + file, MetadataConstants.SAMPLE_BYTES, TIMEOUT_SECONDS);
	}

	/**
	 * One invocation for a whole group of photos, each one its own input. The
	 * samples come back to back, in the order the files were given.
	 *
	 * <p>
	 * The bytes are returned exactly as ffmpeg wrote them, with no attempt to say
	 * which photo each sample belongs to: nothing in the stream identifies one, so
	 * the only thing that pairs a sample with its photo is the position, and the
	 * only thing that makes the position trustworthy is the total being what the
	 * caller asked for. Checking that is the caller's - it is the one that knows
	 * what to fall back to, and it is where a test can reach.
	 */
	public byte[] runGroup(String ffmpegPath, List<Path> files) throws InterruptedException {
		List<String> command = new ArrayList<>(List.of(ffmpegPath, "-v", "error", "-y"));

		// One input per photo, so each keeps its own decoder: see PhotoHashFilterGraph
		// for what sharing one costs.
		for (Path file : files) {
			command.add("-i");
			command.add(file.toAbsolutePath().normalize().toString());
		}

		command.addAll(List.of("-filter_complex", PhotoHashFilterGraph.forPhotos(files.size()), "-map", "[out]",
				"-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "gray", "pipe:1"));

		return decode(new ProcessBuilder(command), ffmpegPath, "group of " + files.size() + " photos",
				MetadataConstants.SAMPLE_BYTES * files.size(),
				TIMEOUT_SECONDS + TIMEOUT_PER_PHOTO_SECONDS * files.size());
	}

	private static byte[] decode(ProcessBuilder builder, String ffmpegPath, String subject, int expectedBytes,
			int timeoutSeconds) throws InterruptedException {
		Process process = ExternalToolProcess.start(builder, ffmpegPath);

		ByteArrayOutputStream output = new ByteArrayOutputStream(expectedBytes);

		StringBuilder errorOutput = new StringBuilder();

		Thread outputReader = drainBinaryAsync(process.getInputStream(), output);

		Thread errorReader = drainTextAsync(process.getErrorStream(), errorOutput);

		try {
			boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

			outputReader.join(TimeUnit.SECONDS.toMillis(2));
			errorReader.join(TimeUnit.SECONDS.toMillis(2));

			if (!finished) {
				throw new IllegalStateException("ffmpeg timed out for " + subject);
			}

			if (process.exitValue() != 0) {
				throw new IllegalStateException("ffmpeg failed for " + subject + ". Exit code: " + process.exitValue()
						+ (errorOutput.isEmpty() ? "" : ". Error: " + errorOutput));
			}

			return output.toByteArray();
		} finally {
			if (process.isAlive()) {
				process.destroyForcibly();
			}
		}
	}

	private static Thread drainBinaryAsync(InputStream stream, ByteArrayOutputStream sink) {
		Thread thread = new Thread(() -> {
			try {
				stream.transferTo(sink);
			} catch (IOException _) {
				// Stream closed because the process was destroyed.
			}
		});

		thread.setDaemon(true);
		thread.start();

		return thread;
	}

	private static Thread drainTextAsync(InputStream stream, StringBuilder sink) {
		Thread thread = new Thread(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
				String line;

				while ((line = reader.readLine()) != null) {
					sink.append(line);
				}
			} catch (IOException _) {
				// Stream closed because the process was destroyed.
			}
		});

		thread.setDaemon(true);
		thread.start();

		return thread;
	}
}