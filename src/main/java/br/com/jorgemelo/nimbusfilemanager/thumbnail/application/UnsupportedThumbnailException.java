package br.com.jorgemelo.nimbusfilemanager.thumbnail.application;

import java.io.IOException;

/**
 * A photo whose thumbnail cannot be produced by the in-JVM decoder - either the
 * format is not decodable ({@code ImageIO} can't read WEBP/HEIC and rejects
 * corrupt files) or the source file is no longer available. This is expected
 * for part of a real library, so callers turn it into "no thumbnail" (HTTP 404)
 * instead of a 500 with a noisy stack trace.
 */
class UnsupportedThumbnailException extends IOException {

	private static final long serialVersionUID = -2379152708019014922L;

	UnsupportedThumbnailException(String message) {
		super(message);
	}
}