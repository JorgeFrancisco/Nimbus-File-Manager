package br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

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

	public byte[] run(String ffmpegPath, Path file) throws InterruptedException {
		ProcessBuilder builder = new ProcessBuilder(ffmpegPath, "-v", "error", "-y", "-i",
				file.toAbsolutePath().normalize().toString(), "-vframes", "1", "-vf",
				"scale=" + MetadataConstants.SAMPLE_SIDE + ":" + MetadataConstants.SAMPLE_SIDE
						+ ":flags=lanczos,format=gray",
				"-f", "rawvideo", "-pix_fmt", "gray", "pipe:1");

		Process process = ExternalToolProcess.start(builder, ffmpegPath);

		ByteArrayOutputStream output = new ByteArrayOutputStream(MetadataConstants.SAMPLE_BYTES);

		StringBuilder errorOutput = new StringBuilder();

		Thread outputReader = drainBinaryAsync(process.getInputStream(), output);

		Thread errorReader = drainTextAsync(process.getErrorStream(), errorOutput);

		try {
			boolean finished = process.waitFor(30, TimeUnit.SECONDS);

			outputReader.join(TimeUnit.SECONDS.toMillis(2));
			errorReader.join(TimeUnit.SECONDS.toMillis(2));

			if (!finished) {
				throw new IllegalStateException("ffmpeg timed out for file: " + file);
			}

			if (process.exitValue() != 0) {
				throw new IllegalStateException("ffmpeg failed for file: " + file + ". Exit code: "
						+ process.exitValue() + (errorOutput.isEmpty() ? "" : ". Error: " + errorOutput));
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