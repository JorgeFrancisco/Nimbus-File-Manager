package br.com.jorgemelo.nimbusfilemanager.backup.application.dto;

/**
 * Announces that every table was just replaced by a backup's contents.
 *
 * <p>
 * A restore rewrites the database under a process that is still running, so
 * anything this run had already read from it is now describing an installation
 * that no longer exists - the configured folders, the caches built from them.
 * The screens showed it plainly: after restoring, the welcome wizard opened
 * again because the watched folder had been read into memory while the table
 * was still empty.
 *
 * <p>
 * An event rather than direct calls, so the domain that restores does not have
 * to know who keeps state derived from the catalog. Whoever does, listens.
 *
 * @param name the backup that was loaded, for the log
 */
public record CatalogRestored(String name) {
}