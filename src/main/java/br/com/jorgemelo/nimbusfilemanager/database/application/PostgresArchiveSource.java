package br.com.jorgemelo.nimbusfilemanager.database.application;

import java.nio.file.Path;

/**
 * Where the packaged PostgreSQL comes from, as a port.
 *
 * <p>
 * Downloading is the part that needs the network and a real server to test
 * against; deciding whether to download, and what to keep out of the archive,
 * is not. The split is what lets the installer be exercised against a zip a
 * test writes itself.
 */
public interface PostgresArchiveSource {

	/**
	 * Fetches the archive into the given folder.
	 *
	 * @return the downloaded file, or {@code null} when it could not be fetched
	 */
	Path download(Path targetFolder);
}