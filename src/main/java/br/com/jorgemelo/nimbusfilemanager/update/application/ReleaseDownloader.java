package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Fetches the files of a release.
 *
 * <p>
 * A port for the same reason {@link ReleaseSource} is one, and with the same
 * payoff: what the installer step has to get right is the verification, and
 * verifying a download that was tampered with, truncated or answered with an
 * error page must be provable without a server willing to do any of those.
 */
public interface ReleaseDownloader {

	/**
	 * @throws IOException when the file could not be fetched in full; a partial
	 * download is a failure rather than a shorter file
	 */
	void download(String url, Path target) throws IOException;

	/** The body of a small text file, such as the published checksum. */
	String readText(String url) throws IOException;
}