package br.com.jorgemelo.nimbusfilemanager.shared.application.catalog;

import java.time.LocalDateTime;
import java.util.List;

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
public interface CatalogMutations {

	/**
	 * Marks catalogued files as missing from disk, as a reconcile pass concludes
	 * after walking the tree. Only real ACTIVE to MISSING transitions are touched,
	 * so a file already missing keeps the timestamp the retention clock counts
	 * from.
	 *
	 * @return how many rows changed state
	 */
	int markMissing(List<Long> catalogFileIds, LocalDateTime changedAt);

	/**
	 * Permanently removes files that have been missing since before the cutoff.
	 * This is the one operation here that destroys history rather than state, and
	 * it is why the retention window is a setting rather than a constant.
	 *
	 * @return how many rows were removed
	 */
	int purgeMissingBefore(LocalDateTime cutoff);

	/**
	 * Removes every catalogued file at or under a library root - what the library
	 * switch does to the collection it is leaving behind. The files on disk are
	 * untouched: this forgets them, it does not delete them.
	 *
	 * @return how many rows were removed
	 */
	int forgetLibrary(String libraryRoot);

	/**
	 * Moves every catalogued file under a folder to where that folder now is.
	 *
	 * <p>
	 * Here rather than in ordinary persistence for the reason the others are: one
	 * rename of one folder decides, for every file beneath it at once, that the
	 * collection is somewhere else. It is also what keeps the promise the rename
	 * makes - that the catalog is right when the operation ends. Left to the next
	 * reconciliation, a renamed folder would leave every file under it looking
	 * missing until a pass got to it, and the screens read the catalog, not the
	 * disk.
	 *
	 * @return how many catalogued files moved with the folder
	 */
	int repointFolder(String oldFolder, String newFolder, LocalDateTime changedAt);
}