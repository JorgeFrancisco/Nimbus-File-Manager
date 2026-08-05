package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.Arrays;

/**
 * The approved pairs of one run, collected as they are found and handed over in
 * the two shapes the run needs them.
 *
 * <p>
 * Every producer of relations does the same bookkeeping and none of it is about
 * the media: three parallel arrays grown by doubling, a count of how much of
 * them is filled, and the adjacency the grouping consults built from exactly
 * that. What differs between a photo and a video is which pairs are approved and
 * why - not how an approval is written down.
 *
 * <p>
 * Deliberately not a general similarity framework. It holds the one piece the
 * builders would otherwise each copy, and knows nothing about hashes, distances,
 * frames or thresholds.
 */
final class RelationAccumulator {

	/**
	 * Enough that an ordinary library never grows the arrays, small enough that a
	 * run over a handful of files does not allocate for a library it does not have.
	 */
	private static final int INITIAL_CAPACITY = 1 << 14;

	private int[] first = new int[INITIAL_CAPACITY];
	private int[] second = new int[INITIAL_CAPACITY];
	private int[] scores = new int[INITIAL_CAPACITY];

	private int count;

	/**
	 * @param left the position of one file of the pair, as the caller indexes them
	 * @param right the position of the other
	 * @param score what the pair scored, which is what reaches the screen
	 */
	void approve(int left, int right, int score) {
		if (count == first.length) {
			first = Arrays.copyOf(first, count * 2);
			second = Arrays.copyOf(second, count * 2);
			scores = Arrays.copyOf(scores, count * 2);
		}

		first[count] = left;
		second[count] = right;
		scores[count] = score;
		count++;
	}

	/**
	 * @param nodes how many candidates exist in total, including those that were
	 * approved with nobody - the adjacency is indexed by position, so it has to
	 * know the positions that hold no relation
	 */
	BuiltRelations toRelations(int nodes) {
		return new BuiltRelations(ApprovedRelations.of(first, second, scores, count, nodes), first, second, scores,
				count);
	}
}