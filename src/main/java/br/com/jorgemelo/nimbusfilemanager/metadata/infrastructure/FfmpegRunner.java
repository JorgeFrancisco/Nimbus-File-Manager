package br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure;

import java.io.IOException;
import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ProcessingMetrics;

@FunctionalInterface
public interface FfmpegRunner {

	byte[] run(String ffmpegPath, Path file, ProcessingMetrics metrics) throws IOException, InterruptedException;
}