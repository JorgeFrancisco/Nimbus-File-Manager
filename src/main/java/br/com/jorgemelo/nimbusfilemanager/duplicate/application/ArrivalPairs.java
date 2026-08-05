package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.Arrays;

/**
 * Which pairs an arrival is about, and how they are written down - for whichever
 * medium is asking.
 *
 * <p>
 * The rule is the same for photos and for videos because it is not about the
 * comparison: each newcomer against every file already incorporated, plus the
 * newcomers against each other, and never a pair of two covered files. That last
 * exclusion is the whole economy of the incremental path, and it is safe for the
 * same reason in both media - whether two files look alike is a fact about the
 * two of them, and neither was touched.
 *
 * <p>
 * What is deliberately <b>not</b> here is the loop. Each medium walks its own
 * data in the layout that suits it: the photo scan hoists a hash's four words
 * out of the inner loop, which an enumerator handing over one pair at a time
 * would make impossible, and at a hundred million pairs per arrival that is not
 * a stylistic preference. So what is shared is the rule and the bookkeeping -
 * the parts that could silently diverge - and each builder keeps the three lines
 * that read its own bytes.
 */
final class ArrivalPairs {

	private ArrivalPairs() {
	}

	/**
	 * Whether this pair has already been counted from the other end, or is not a
	 * pair at all.
	 *
	 * <p>
	 * A pair of two newcomers is reachable from both of them, so taking it only
	 * from the lower index keeps it exactly once. A pair with a covered file in it
	 * is reachable from one end only, and a file is not its own pair. Skipping the
	 * covered-covered pairs is not an optimisation: it is the claim the coverage
	 * table makes.
	 */
	static boolean alreadyTaken(boolean[] newcomer, int left, int right) {
		return right == left || (newcomer[right] && right < left);
	}

	/**
	 * The buffer, grown by doubling when it is full. Apart from the scan because
	 * the reallocation is bookkeeping and the loop it sat in is the measurement.
	 */
	static long[] withRoomFor(long[] pairs, int found) {
		return found == pairs.length ? Arrays.copyOf(pairs, found * 2) : pairs;
	}

	/** The pair as one long, lower index first, so it sorts into scan order. */
	static long pack(int left, int right) {
		return ((long) Math.min(left, right) << 32) | Math.max(left, right);
	}

	static int left(long pair) {
		return (int) (pair >>> 32);
	}

	static int right(long pair) {
		return (int) pair;
	}

	/**
	 * The survivors, trimmed and in the order a full scan would have produced them.
	 * The grouping is order-dependent, and a packed pair sorts by its lower index
	 * first - which is exactly that order.
	 */
	static long[] sorted(long[] pairs, int found) {
		long[] survivors = Arrays.copyOf(pairs, found);

		Arrays.sort(survivors);

		return survivors;
	}
}