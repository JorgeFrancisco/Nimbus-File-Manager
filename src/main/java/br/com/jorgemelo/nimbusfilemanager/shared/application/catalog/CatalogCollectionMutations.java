package br.com.jorgemelo.nimbusfilemanager.shared.application.catalog;

import java.time.Instant;

/**
 * The capacity to change what the catalog says about the collection as a whole.
 *
 * <p>
 * Not a repository, and deliberately not shaped like one. Saving a fingerprint,
 * recording an execution's progress or writing the metadata of one file are
 * ordinary persistence and belong nowhere near here: they describe a single
 * thing that was examined. What passes through this port is the other kind of
 * write - the one that decides, for a whole set of files at once, that they are
 * gone, or missing, or no longer part of anything. Those are the writes whose
 * blast radius is the user's collection rather than one row, and the ones that
 * a screen must never be able to trigger by accident.
 *
 * <p>
 * Four operations, because four exist. It is not a CRUD surface waiting to be
 * filled: an operation is added here when a workload needs to change the
 * collection in a way the existing ones do not express, and that addition is a
 * decision rather than a convenience.

 */
public interface CatalogCollectionMutations {
	/**
	 * Permanently removes files that have been missing since before the cutoff.
	 * This is the one operation here that destroys history rather than state, and
	 * it is why the retention window is a setting rather than a constant.
	 *
	 * @return how many rows were removed
	 */
	int purgeMissingBefore(Instant cutoff);

	/**
	 * Removes every catalogued file at or under a library root - what the library
	 * switch does to the collection it is leaving behind. The files on disk are
	 * untouched: this forgets them, it does not delete them.
	 *
	 * @return how many rows were removed
	 */
	int forgetLibrary(String libraryRoot);
}