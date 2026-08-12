package br.com.jorgemelo.nimbusfilemanager.catalog.domain.enums;

/**
 * What a look at a file says about the bytes the catalog believes it holds.
 *
 * <p>
 * Four answers rather than a boolean, because "changed" and "not changed" leave
 * out the two that matter most: finding out for the first time what a file
 * contains is not a change, and being unable to tell is not the same as being
 * able to say no.
 */
public enum ContentVerdict {

	/**
	 * The bytes the catalog knows are the bytes that are there - either a digest
	 * agreed with the stored one, or nothing observable suggests otherwise.
	 *
	 * <p>
	 * The second case is the product's stated limit rather than a proof: an edit
	 * that preserves both size and modification time, and produces no event, is
	 * not visible to anything short of reading the file. What is promised is
	 * event-driven detection with a reconciliation behind it, and a periodic
	 * cryptographic sweep is a decision to be taken on measurement.
	 */
	UNCHANGED,

	/**
	 * A digest was observed for a file that had none. The catalog knows more than
	 * it did, and nothing was proved to have happened: a file nobody had hashed
	 * has no previous content to differ from.
	 */
	HASH_LEARNED,

	/**
	 * A digest was observed and it disagrees with the stored one. This is the only
	 * verdict that proves the content is not what the catalog says, and the only
	 * one that advances the content revision.
	 */
	CONTENT_CHANGED,

	/**
	 * Something suggests the content may differ and no digest was available to
	 * settle it - a size or a modification time that moved, or the object at that
	 * path having been swapped for another. It asks for a read; it asserts
	 * nothing, and no fact is recorded for it.
	 */
	NEEDS_VERIFICATION
}