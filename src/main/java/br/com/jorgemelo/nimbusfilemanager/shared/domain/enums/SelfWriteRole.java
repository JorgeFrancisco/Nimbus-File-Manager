package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

/**
 * Which half of an effect an announcement explains.
 *
 * <p>
 * An announcement that only named a path read as "ignore anything that happens
 * here for a while", which is more than this product ever knows. Moving a file
 * out of a folder says that path will be emptied; it says nothing about a file
 * the user drops there a minute later - and a path just freed is a path likely
 * to be reused.
 */
public enum SelfWriteRole {

	/**
	 * A path this product is emptying: the source of a move, a file being
	 * deleted. It explains a disappearance, and never an arrival.
	 */
	VACATING,

	/**
	 * A path this product is filling: the destination of a move, a file whose
	 * timestamp is being carried over. It explains an arrival, and never a
	 * disappearance.
	 */
	OCCUPYING
}