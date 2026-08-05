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
	 * policy anybody chose; the column that records it is declared in V22.
	 *
	 * <p>
	 * The value carries {@code ELIGIBLE} because the cap changed meaning, and the
	 * change had to be visible. It used to cut the first N rows by id and remove
	 * the user's exclusions from what came back, so a run announced as N files
	 * analysed far fewer; it now cuts the first N <em>eligible</em> files. Both
	 * answers are legitimate results of the policy that produced them, and neither
	 * should be mistaken for the other - so the string differs, the digest differs,
	 * and a result computed under the old rule lives on as its own family instead
	 * of being silently treated as this one. Nothing rewrites those older rows.
	 */
	public static final String SELECTION_OLDEST_ELIGIBLE_FIRST = "OLDEST_ELIGIBLE_ID_FIRST";

	/**
	 * What a recorded candidate limit of zero means: the analysis stopped at no
	 * number of files, having examined every eligible one.
	 *
	 * <p>
	 * Zero rather than a very large number, because a large number would be a cap
	 * that happens not to bind - and the screen would then have to guess whether
	 * "8.000 of 119.830" and "200.000 of 119.830" mean the same thing. Nothing
	 * divides by it: whether a result is partial is decided by comparing what was
	 * analysed with what was eligible, not by looking at this.
	 */
	public static final int NO_CANDIDATE_LIMIT = 0;

	private SimilarityConstants() {
	}
}