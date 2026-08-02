package br.com.jorgemelo.nimbusfilemanager.metadata.application;

/**
 * The external program could not be started at all - not found, not executable,
 * or named by a path that no longer exists.
 *
 * <p>
 * Distinct from every other failure of a media operation because it says
 * nothing about the file. Without it the two are one
 * {@code IllegalStateException} with the same wording, and the code that
 * explains a failure has to fall back to reading the file's bytes - which,
 * faced with a perfectly good JPEG the decoder never opened, concluded the
 * stream was refused and wrote the photo off permanently. A whole run's worth
 * of files was written off that way, for a saved ffmpeg path that pointed at a
 * folder from an older layout.
 */
public class ExternalToolNotRunnableException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	public ExternalToolNotRunnableException(String executable, Throwable cause) {
		super("Could not start the external tool: " + executable + ". " + cause.getMessage(), cause);
	}
}