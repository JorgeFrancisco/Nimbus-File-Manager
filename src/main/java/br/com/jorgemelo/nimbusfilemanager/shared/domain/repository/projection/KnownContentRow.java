package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection;

import java.time.Instant;

/**
 * What the catalog holds about one file's bytes, read in a single query.
 *
 * <p>
 * A projection rather than the entities because the caller is the watcher's poll
 * thread: it asks this every time the operating system reports a write, and
 * walking from a file to its location to find an identity would be two lazy
 * loads and a transaction on the thread that also has to notice a
 * reconfiguration.
 */
public interface KnownContentRow {

	Long getCatalogFileId();

	String getSha256();

	Long getSizeBytes();

	Instant getModifiedAt();

	Long getContentRevision();

	String getFilesystemIdentityKind();

	String getFilesystemIdentityScope();

	String getFilesystemIdentityValue();
}