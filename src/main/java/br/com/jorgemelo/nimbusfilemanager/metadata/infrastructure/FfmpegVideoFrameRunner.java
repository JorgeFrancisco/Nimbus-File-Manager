package br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure;

import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoFrameSamplingPlan;

/**
 * Seam over the single ffmpeg pass that extracts a video's sampled frames as
 * raw grayscale bytes. Kept as a functional interface so tests substitute the
 * process spawn with a lambda, exactly like the photo {@code FfmpegRunner}.
 */
@FunctionalInterface
public interface FfmpegVideoFrameRunner {

	byte[] run(String ffmpegPath, Path file, VideoFrameSamplingPlan plan) throws Exception;
}