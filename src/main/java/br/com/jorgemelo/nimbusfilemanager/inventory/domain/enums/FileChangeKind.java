package br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums;

/**
 * What a file-system source observed about one entry.
 *
 * <p>
 * Normalized on purpose. The three sources name the same handful of things
 * differently - {@code USN_REASON_*} bitmasks, {@code FILE_ACTION_*} codes,
 * {@code ENTRY_*} kinds - and nothing above the source should have to know
 * which one it is listening to.
 *
 * <p>
 * Losing events is deliberately not one of these. It is not something that
 * happened to an entry; it is a source admitting it cannot account for its
 * window, and it travels as {@link WatchRecoveryReason}, which says which of
 * three very different situations it was - a distinction one more kind here
 * would collapse back into the boolean it used to be.
 */
public enum FileChangeKind {

	/** The entry appeared at this path. */
	CREATED,

	/** The entry's content changed where it already was. */
	MODIFIED,

	/**
	 * The entry left this path and the source knows of no destination inside the
	 * watched root - it was deleted, or it was moved somewhere nobody is watching.
	 */
	DELETED,

	/**
	 * The entry moved, and the source knows both ends. Only a source that pairs
	 * the two halves itself reports this; the others report a departure and an
	 * arrival that nothing has yet joined.
	 */
	RENAMED
}