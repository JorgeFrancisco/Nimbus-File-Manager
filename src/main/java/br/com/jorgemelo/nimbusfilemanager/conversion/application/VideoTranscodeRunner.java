package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.TranscodeExecution;

/**
 * The seam between the conversion logic and the actual ffmpeg process. The
 * application only ever hands over a finished command line and receives the
 * outcome, so the process handling (streams, timeout, kill) lives entirely in
 * the infrastructure adapter and unit tests can drive the whole conversion
 * without spawning anything.
 */
@FunctionalInterface
public interface VideoTranscodeRunner {

	/**
	 * Runs {@code command}, feeding every {@code -progress} line to
	 * {@code progressLines} as it arrives and killing the process as soon as
	 * {@code cancelled} turns true - an encode can run for hours, so waiting for it
	 * to end on its own is not a cancellation at all.
	 */
	TranscodeExecution run(List<String> command, Consumer<String> progressLines, BooleanSupplier cancelled)
			throws IOException, InterruptedException;
}