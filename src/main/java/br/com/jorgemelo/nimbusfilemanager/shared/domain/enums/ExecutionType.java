package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

public enum ExecutionType {

	INVENTORY, ORGANIZATION, ORGANIZATION_PREVIEW, UNDO, QUARANTINE_RESTORE, QUARANTINE_PURGE,
	QUARANTINE_CLEANUP, DEDUP_DELETE, RECONCILE,
	CONVERSION,

	/**
	 * The three the Files screen asks for. One type each rather than one Explorer
	 * type carrying an action: the dispatcher picks a handler by this value, the
	 * screen names it, and - the reason that decides it - deduplication is indexed
	 * on the type together with the key, so a single type would make renaming and
	 * deleting the same path look like the same request.
	 */
	EXPLORER_RENAME, EXPLORER_QUARANTINE, EXPLORER_DELETE,

	/**
	 * Removing catalog entries whose file has been missing for longer than the
	 * retention window. Its own type rather than {@link #QUARANTINE_PURGE}: that
	 * one erases files a person chose to delete, this one forgets rows about files
	 * that were already gone, and a screen naming them alike would be describing
	 * two very different afternoons.
	 */
	CATALOG_PURGE, LIBRARY_SWITCH,

	/**
	 * Grouping visually similar media. One type per medium rather than one carrying
	 * the medium as data, for the reason the Explorer types already established:
	 * the dispatcher picks a handler by this value, and its concurrency is counted
	 * per type - so a single type would mean a photo analysis and a video analysis
	 * competing for the same slot.
	 *
	 * <p>
	 * They are separate slots because that is what the two background runners
	 * already were, each with its own flag, over a pool of two threads: never two
	 * of the same medium at once, and a photo grouping may overlap a video one.
	 * Both spend their time in the same SSIM in CPU, and the video one also holds
	 * every sampled frame of its candidates in memory, which is why neither gets
	 * more than one.
	 */
	SIMILARITY_PHOTO, SIMILARITY_VIDEO,

	FINGERPRINT_PHOTO, FINGERPRINT_VIDEO,

	/**
	 * Re-reading the metadata of files already catalogued, and the dry run that
	 * says what such a pass would change. One type for both because they are the
	 * same walk over the same folder with the same tools - what differs is whether
	 * the answer is written to the catalog or only shown, which is a flag in the
	 * request and not a different kind of work to schedule.
	 */
	METADATA_REBUILD, CONTENT_VERIFICATION,

	/**
	 * Resolving the location of media that already has coordinates, and acquiring
	 * the boundary dataset those answers are read from.
	 *
	 * <p>
	 * Two types, one resource. Both declare the geodata folder as their path, so
	 * the advisory lock every execution already takes is what keeps them apart -
	 * across processes, and without either of them having to know the other
	 * exists. Replacing the boundaries under a running rebuild would change the
	 * answers halfway through; rebuilding against boundaries being replaced would
	 * read half of each.
	 */
	LOCATION_REBUILD, GEO_DATASET_UPDATE
}