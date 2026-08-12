package br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums;

/**
 * How many catalogued places an observed identity turned out to name.
 *
 * <p>
 * Three answers and not an optional, because the third is not "no" and must not
 * be reachable by taking the first of a list. A hard link is one object with two
 * names, so two locations legitimately carry one identity - which means an
 * identity cannot always say which row it is about, and the honest reply is that
 * it cannot.
 */
public enum LocationMatch {

	/** Nothing carries this identity - a file the catalog has not met yet. */
	NONE,

	/** Exactly one location carries it, so continuity is established. */
	UNIQUE,

	/**
	 * More than one does. Nothing is chosen: the ordinary pass, which compares the
	 * whole tree, is in a position to settle it and a single observation is not.
	 */
	AMBIGUOUS
}