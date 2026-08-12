package br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ProcessingMetrics;

/**
 * One ffmpeg invocation covering a whole group of photos, each of them an input
 * of its own.
 *
 * <p>
 * Separate from {@link FfmpegRunner} because the two say different things about
 * their output: that one returns a single sample, this one returns one per file
 * - and only the count makes the order mean anything.
 */
@FunctionalInterface
public interface FfmpegGroupRunner {

	byte[] run(String ffmpegPath, List<Path> files, ProcessingMetrics metrics) throws IOException, InterruptedException;
}