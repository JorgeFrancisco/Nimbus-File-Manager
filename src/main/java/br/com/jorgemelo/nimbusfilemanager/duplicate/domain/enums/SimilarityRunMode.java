package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums;

/**
 * How a queued similarity analysis is to reach its answer.
 *
 * <ul>
 * <li>{@code REBUILD} - compare every pair again. The only mode that can
 * discover a relation, and therefore the only one that can take into account a
 * file the product has never compared.</li>
 * <li>{@code REGROUP} - group the relations already approved, comparing
 * nothing. Correct exactly when the change was a removal: the relations of the
 * files that left are simply not read, and the ones that stay were not made
 * false by their leaving.</li>
 * <li>{@code ADD} - compare only the pairs an arrival creates, then group. The
 * files nobody has incorporated yet are measured against every file that was,
 * and against each other; the pairs of two old files are not asked about again,
 * because neither of the two changed.</li>
 * </ul>
 *
 * <p>
 * The distinction is not a performance setting. Regrouping after a file
 * <em>arrives</em> would publish an answer that never looked at it, so which of
 * the two is being asked for is part of what the request means and travels with
 * it.
 *
 * <p>
 * {@code ADD} and {@code REBUILD} reach the same answer over the same final set
 * - that is what {@code SimilarityIncrementalEquivalenceTest} and
 * {@code PhotoSimilarityAddEquivalenceTest} hold - and differ in what they pay
 * for it. Which one a request should use is therefore a question about cost,
 * and the only mode that can be wrong for an arrival is {@code REGROUP}.
 */
public enum SimilarityRunMode {

	REBUILD, REGROUP, ADD
}