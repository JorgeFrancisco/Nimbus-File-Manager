package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

/**
 * What decides whether two files relate: which fingerprint algorithm produced
 * the hashes, how far apart they were allowed to be, what similarity they had to
 * reach, and - for a medium whose comparison takes more than that - a digest of
 * the rest.
 *
 * <p>
 * They travel together because they are one thing - the key
 * {@code similarity_relation} is stored under - and passing them apart is what
 * made every method of the writer carry eight arguments. Grouping them is also
 * what let the batching move into a method of its own without that method
 * inheriting the same problem.
 *
 * <p>
 * The fourth is empty for photos and holds the video comparison's quorum, trim
 * and tolerances for videos. A digest rather than four more fields because the
 * shared storage should not grow a column per medium, and because what a reader
 * needs from it is only whether two runs used the same settings. What it must
 * never absorb is anything the other three already say, or the two would
 * disagree about the same fact.
 *
 * <p>
 * Deliberately narrower than {@code SimilarityGrouping.parametersDigest}, which
 * also carries the exclusion signature and the selection policy. Those decide
 * <em>which files enter an analysis</em>, not whether two of them look alike, so
 * a relation keyed by them would be thrown away every time a folder was hidden.
 */
public record RelationParameters(String algorithmId, int maxDistance, int minSimilarity, String relationDigest) {

	/**
	 * What a medium stores when the three named parameters are its whole list.
	 * Empty rather than a hash of nothing, so the column reads as what it means
	 * and the rows written before the column existed keep their identity.
	 */
	public static final String NO_MEDIA_PARAMETERS = "";

	public RelationParameters(String algorithmId, int maxDistance, int minSimilarity) {
		this(algorithmId, maxDistance, minSimilarity, NO_MEDIA_PARAMETERS);
	}
}