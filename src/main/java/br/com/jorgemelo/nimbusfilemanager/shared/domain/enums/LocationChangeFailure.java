package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

/**
 * Why the catalog refused to move a file.
 *
 * <p>
 * A closed set, and that is the point of it. The database raises each of these
 * as its own SQLSTATE and the write door turns the code into one of these
 * constants, so a caller decides what to do by matching a value rather than by
 * reading a sentence - which is what made every previous "already exists" check
 * in this product a string comparison waiting to be broken by a reworded
 * message.
 */
public enum LocationChangeFailure {

	/** No file with that identity, so there is nothing to move. */
	CATALOG_FILE_NOT_FOUND,

	/**
	 * The file is known but has no placement on record. Not the same as not
	 * existing: the catalog knows the file and has lost where it is, which is
	 * damage rather than a missing argument.
	 */
	LOCATION_NOT_FOUND,

	/**
	 * The file is not where the caller believed it was. Whatever the caller decided
	 * from its own reading - that a name was free, that a folder was the right one
	 * - it decided from a view that has since moved on.
	 */
	STALE_LOCATION,

	/**
	 * Another file is present at the destination. Only a file actually there
	 * blocks: one that went missing from the path, or was removed, is remembered
	 * rather than in the way.
	 */
	PATH_OCCUPIED,

	/**
	 * The same event identity was already recorded describing a different change.
	 * A retry replaying its own work is not this - that succeeds silently. This is
	 * two different changes claiming to be the same one.
	 */
	IDEMPOTENCY_CONFLICT,

	/** Something the door needs to identify the change was not supplied. */
	INVALID_ARGUMENT,

	/**
	 * The change is not the kind that was asked for - a rename that leaves its
	 * folder, or a move that stays in it. Always a defect in the caller, never
	 * something a user did.
	 */
	INVALID_CHANGE,

	/**
	 * Two files were found present at one path. The catalog is not supposed to be
	 * able to reach this state, and reaching it means an earlier write bypassed the
	 * door.
	 */
	MULTIPLE_PRESENT_FILES
}