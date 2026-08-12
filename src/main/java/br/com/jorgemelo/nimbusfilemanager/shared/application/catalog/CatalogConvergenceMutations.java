package br.com.jorgemelo.nimbusfilemanager.shared.application.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;

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
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;

 */
public interface CatalogConvergenceMutations {
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
	 * <p>
	 * One fact per file, not one for the folder: a folder is not something the
	 * catalog knows, and what a screen or a later reconciliation needs to read is
	 * where each file went.
	 *
	 * @param evidence what proves the classification of every fact this derives.
	 * Structural, like {@code source}: the door is told what established the change
	 * and never which feature asked for it
	 * @return how many catalogued files moved with the folder
	 */
	int repointFolder(String oldFolder, String newFolder, Instant occurredAt, String source, String evidence);

	/**
	 * The same folder relocation, applied to a set somebody already decided on and
	 * whose facts already have identities.
	 *
	 * <p>
	 * For the capability that emits the operation rather than observing one: it
	 * reserved a movement per file before touching the disk, and each of those
	 * carries the identity of the fact it will produce. Minting fresh ones here
	 * would leave a retry unable to recognise its own work.
	 */
	int repointFolder(String oldFolder, String newFolder, List<Long> catalogFileIds,
			List<UUID> catalogFileEventPublicIds, CatalogFactProvenance provenance);
}