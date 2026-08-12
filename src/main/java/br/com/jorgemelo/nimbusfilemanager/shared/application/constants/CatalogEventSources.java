package br.com.jorgemelo.nimbusfilemanager.shared.application.constants;

/**
 * What produced a fact in the catalog's history.
 *
 * <p>
 * Strings and not an enum, matching the column: the history outlives the code
 * that wrote it, and a row recorded by something that no longer exists has to
 * stay readable rather than fail to load. What this holder buys is the other
 * half - that two features writing the same source spell it the same way, which
 * is what makes the history queryable at all.
 */
public final class CatalogEventSources {

	/** A rename or a move the user asked for from the Files screen. */
	public static final String EXPLORER = "EXPLORER";

	/** A file relocated by an organization run, or put back by its undo. */
	public static final String ORGANIZATION = "ORGANIZATION";

	/** A file sent to the quarantine folder, whichever feature asked for it. */
	public static final String QUARANTINE = "QUARANTINE";

	/**
	 * The library watch: a change something outside this application made, seen
	 * as it happened or recovered from the journal afterwards.
	 *
	 * <p>
	 * One value for the capability and not one per mechanism, which is the
	 * granularity the three above it already use. Which detector saw it - the USN
	 * journal, the live directory watch - is a fact about the machinery rather
	 * than about what happened to the file, and what it bought is already carried
	 * by the evidence: only some of them can supply an identity at all.
	 */
	public static final String WATCHER = "WATCHER";

	/**
	 * A walk of the library: the pass that visits every file and compares what it
	 * finds against what the catalog holds.
	 */
	public static final String INVENTORY = "INVENTORY";

	/**
	 * The pass that compares the catalog against the disk. Distinct from the
	 * inventory beside it: one walks to find what it does not know, this one walks
	 * to check what it does.
	 */
	public static final String RECONCILE = "RECONCILE";

	private CatalogEventSources() {
	}
}