package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection;

/**
 * One file the catalog already knows whose last known place is one of the paths
 * being asked about.
 *
 * <p>
 * A row per match rather than a map entry, because a path can match more than
 * one file: the one that went missing from it and the one that arrived after.
 * Collapsing that into a single answer is precisely what the old
 * {@code file_key} lookup did, and it is how a new file inherited the identity
 * of a different one.
 *
 * <p>
 * The path comes back as it went in, so the caller can match its own input
 * without knowing how the database canonicalized it.
 */
public interface CatalogPathMatchRow {

	String getInputPath();

	Long getCatalogFileId();

	String getLifecycleStatus();
}