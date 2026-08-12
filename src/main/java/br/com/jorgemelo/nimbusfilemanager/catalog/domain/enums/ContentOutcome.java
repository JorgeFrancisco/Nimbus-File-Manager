package br.com.jorgemelo.nimbusfilemanager.catalog.domain.enums;

/**
 * What the database made of an attempt to change what a file is known to
 * contain.
 *
 * <p>
 * Four answers because the caller does something different with each, and
 * because collapsing them is how a last-write-wins gets written by accident:
 * two of these mean "do not retry", one means "look again", and only one means
 * anything changed.
 */
public enum ContentOutcome {

	/** The catalog was where the caller said, and moved. Exactly one fact. */
	APPLIED,

	/**
	 * The catalog already holds the digest being reported. Somebody else applied
	 * this transition - nothing written, no second fact, no second increment.
	 */
	ALREADY_CONVERGED,

	/**
	 * The catalog has moved to something the caller has not seen. Its reading is
	 * old and the file has to be looked at again; overwriting on this would be a
	 * last write winning over a newer truth.
	 */
	STALE_OBSERVATION,

	/**
	 * The revision is the expected one but the digest is not, so the catalog is
	 * in a state nothing was supposed to be able to put it in. Unlike a stale
	 * observation, reading the file again would not settle it.
	 */
	CONFLICT
}