package br.com.jorgemelo.nimbusfilemanager.thumbnail.infrastructure;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Runs the external ffmpeg process to extract a single video frame as a JPEG
 * thumbnail. Kept as a seam so {@link VideoThumbnailService} can be unit-tested
 * without spawning a real process. This is distinct from the metadata package's
 * runner: it carries its own width/seek arguments.
 */
@FunctionalInterface
public interface FfmpegRunner {

	void run(String ffmpeg, Path source, Path target, int width, double seek) throws IOException, InterruptedException;
}