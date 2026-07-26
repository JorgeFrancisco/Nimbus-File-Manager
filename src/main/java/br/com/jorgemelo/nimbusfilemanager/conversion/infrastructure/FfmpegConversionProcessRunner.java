package br.com.jorgemelo.nimbusfilemanager.conversion.infrastructure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.VideoTranscodeRunner;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.TranscodeExecution;

/**
 * External-process glue for the H.265 encode: spawns ffmpeg, streams its
 * {@code -progress} output back to the caller line by line, and keeps the tail
 * of stderr so a failure can be explained (and, for an audio/container
 * mismatch, retried with AAC).
 *
 * <p>
 * Unlike every other ffmpeg call in the application this one runs for minutes
 * or hours, so there is no wall-clock timeout: the encode is bounded by the
 * process itself and stdout reaching EOF means ffmpeg is done. The short wait
 * after EOF only catches a process that closed its pipes but never exited. What
 * does end it early is a cancellation: the process is destroyed as soon as the
 * caller asks, between two progress lines. Isolated in its own
 * {@code *ProcessRunner} (excluded from coverage) because spawning cannot be
 * meaningfully unit-tested; all the decision logic lives in
 * {@code VideoTranscoder} and the command in
 * {@code VideoConversionCommandBuilder}.
 */
@Component
public class FfmpegConversionProcessRunner implements VideoTranscodeRunner {

	private static final long EXIT_TIMEOUT_SECONDS = 60;
	private static final long STDERR_DRAIN_TIMEOUT_SECONDS = 5;

	/**
	 * Only the tail of stderr is kept: ffmpeg can emit a very long list of warnings
	 * on a damaged file, and the actual reason it stopped is always at the end.
	 */
	private static final int MAX_ERROR_CHARACTERS = 8_000;

	@Override
	public TranscodeExecution run(List<String> command, Consumer<String> progressLines, BooleanSupplier cancelled)
			throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command).start();

		// The drain publishes the captured tail exactly once, when stderr reaches EOF,
		// so the two threads never share mutable state and no locking is needed.
		CompletableFuture<String> errorOutput = drainAsync(process.getErrorStream());

		boolean killed = false;

		try (BufferedReader stdout = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;

			while ((line = stdout.readLine()) != null) {
				progressLines.accept(line);

				// ffmpeg reports progress about twice a second, so checking here is what
				// makes a cancellation feel immediate on a file that would otherwise run for
				// hours. The half-written output is the caller's to delete.
				if (cancelled.getAsBoolean()) {
					process.destroy();

					killed = true;

					break;
				}
			}
		}

		if (killed) {
			process.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

			return new TranscodeExecution(false, -1, "FFmpeg was stopped on request");
		}

		if (!process.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
			process.destroyForcibly();

			return new TranscodeExecution(false, -1, "FFmpeg did not exit after closing its output");
		}

		return new TranscodeExecution(true, process.exitValue(), capturedError(errorOutput));
	}

	/**
	 * The tail of stderr, or an empty string when the drain has not finished in
	 * time - a diagnostic detail is never worth blocking the batch for.
	 */
	private String capturedError(CompletableFuture<String> errorOutput) {
		return errorOutput.completeOnTimeout("", STDERR_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
	}

	private CompletableFuture<String> drainAsync(InputStream stream) {
		CompletableFuture<String> captured = new CompletableFuture<>();

		Thread thread = new Thread(() -> {
			StringBuilder sink = new StringBuilder();

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				String line;

				while ((line = reader.readLine()) != null) {
					append(sink, line);
				}
			} catch (IOException _) {
				// The process is exiting; a broken drain pipe is not actionable.
			}

			captured.complete(sink.toString());
		});

		thread.setDaemon(true);
		thread.start();

		return captured;
	}

	private void append(StringBuilder sink, String line) {
		sink.append(line).append(System.lineSeparator());

		if (sink.length() > MAX_ERROR_CHARACTERS) {
			sink.delete(0, sink.length() - MAX_ERROR_CHARACTERS);
		}
	}
}