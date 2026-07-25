package br.com.jorgemelo.nimbusfilemanager.metadata.application;

/**
 * A cataloged "video" that cannot yield frame samples for a perceptual hash -
 * no readable duration, a zero-frame or undecodable stream. Like its photo
 * counterpart it is a terminal (non-retryable) condition, so the backlog marks
 * it exhausted immediately instead of retrying a file that will never decode.
 */
public class UnsupportedVideoFingerprintException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	public UnsupportedVideoFingerprintException(String message) {
		super(message);
	}
}