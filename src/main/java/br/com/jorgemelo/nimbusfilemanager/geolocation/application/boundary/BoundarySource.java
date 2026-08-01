package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import java.nio.file.Path;
import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LeveledBoundaryFile;

/**
 * Acquires the administrative-boundary dataset (one GeoJSON file per admin
 * level) and makes the files available locally, ready for import. This is the
 * seam that keeps the architecture independent of distribution: today an
 * official-download implementation, but an embedded, regional or fully offline
 * source can replace it without touching the importer, resolver or the rest of
 * the application.
 */
public interface BoundarySource {

	/**
	 * Ensures the dataset is present under {@code workspaceFolder} and returns the
	 * per-level GeoJSON files to import. Blocking.
	 */
	List<LeveledBoundaryFile> fetch(Path workspaceFolder);

	/**
	 * Optional: acquires the files of individual territories whose ISO codes are
	 * absent from the main dataset (dependent territories dissolved into their
	 * sovereign state) - the country polygon plus any deeper levels the source
	 * knows. Implementations must skip codes they have no data for and never fail
	 * the whole update because of a single territory. Blocking.
	 */
	default List<LeveledBoundaryFile> fetchMissingCountries(List<String> alpha3Codes, Path workspaceFolder) {
		return List.of();
	}

	/**
	 * Publishes what {@link #fetch} acquired, replacing the files the application
	 * reads. Called only after the import succeeded, so the dataset on disk and the
	 * rows in the database always describe the same version.
	 */
	default void commit(Path workspaceFolder) {
		// A source that acquires nothing (an embedded or local-folder one) has
		// nothing to publish and nothing to roll back.
	}

	/**
	 * Drops what {@link #fetch} acquired, leaving the previous dataset in place.
	 * Called when the download or the import failed.
	 */
	default void discard(Path workspaceFolder) {
		// Same as commit: nothing acquired, nothing to undo.
	}

	/** Human-readable provider label for the admin screen. */
	String providerLabel();

	/** License label (attribution) for the admin screen. */
	String license();

	/** Source tag persisted with each imported boundary. */
	String sourceTag();

	/** Version identifier of the acquired dataset. */
	String version();
}