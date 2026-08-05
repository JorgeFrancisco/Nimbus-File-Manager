package br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants;

/**
 * Contract data of the durable similarity result.
 */
public final class SimilarityConstants {

	/**
	 * The version of the grouping logic itself, independent of fingerprints and of
	 * parameters.
	 *
	 * <p>
	 * It exists for the change nothing else can see: the clustering rule, the
	 * ordering of the groups, the way a percentage is derived. Fingerprints and
	 * parameters can be identical across two releases and the groups still differ,
	 * and without this a result produced by the old rule would be served by the new
	 * one - silently, because every other component of the key would match.
	 *
	 * <p>
	 * <b>Increment this whenever the grouping logic changes.</b> Results of an
	 * older version simply stop matching the current key and are never read again.
	 */
	public static final int GROUPING_VERSION = 1;

	/**
	 * How the analysed subset is chosen when there are more eligible files than the
	 * candidate cap allows.
	 *
	 * <p>
	 * Recorded with the result rather than assumed, because it is the difference
	 * between "these are the groups of your library" and "these are the groups of
	 * the part of your library that was looked at". Today it is the oldest
	 * catalogued ids - a consequence of the stable ordering the query needs, not a
	 * policy anybody chose (see V28).
	 */
	public static final String SELECTION_OLDEST_FIRST = "OLDEST_ID_FIRST";

	private SimilarityConstants() {
	}
}