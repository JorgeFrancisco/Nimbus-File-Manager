package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionFailure;

/**
 * Outcome of converting one file. On success {@code output} points at the
 * validated file still sitting in the workspace, never in the library.
 * {@code audioFallback}, {@code subtitlesDropped} and {@code dataDropped}
 * record what had to be given up for MP4 to accept the file, so the report can
 * say so instead of silently losing a track.
 */
public record TranscodeResult(Path output, boolean audioFallback, boolean subtitlesDropped, boolean dataDropped,
		long elapsedMillis, ConversionFailure failure) {

	public static TranscodeResult converted(Path output, boolean audioFallback, boolean subtitlesDropped,
			boolean dataDropped, long elapsedMillis) {
		return new TranscodeResult(output, audioFallback, subtitlesDropped, dataDropped, elapsedMillis, null);
	}

	public static TranscodeResult failed(ConversionFailure failure, boolean audioFallback, boolean subtitlesDropped,
			boolean dataDropped, long elapsedMillis) {
		return new TranscodeResult(null, audioFallback, subtitlesDropped, dataDropped, elapsedMillis, failure);
	}

	public boolean successful() {
		return failure == null;
	}
}