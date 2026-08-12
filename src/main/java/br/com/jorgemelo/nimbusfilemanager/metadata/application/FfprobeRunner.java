package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.io.IOException;
import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.FfprobeResult;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ProcessingMetrics;

@FunctionalInterface
interface FfprobeRunner {

	FfprobeResult run(String ffprobePath, Path file, ProcessingMetrics metrics)
			throws IOException, InterruptedException;
}