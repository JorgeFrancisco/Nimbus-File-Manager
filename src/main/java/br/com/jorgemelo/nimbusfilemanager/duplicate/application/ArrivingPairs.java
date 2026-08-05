package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

/**
 * The pairs an arrival creates, and only those.
 *
 * <p>
 * A rebuild asks about every pair, which is n²/2 and on a real library 7,18
 * billion of them. An arrival asks about far fewer: each new photo against
 * everything already incorporated, plus the new photos against each other. For
 * one photo that is the size of the library, not its square - 120 thousand
 * comparisons instead of billions, and that ratio is the whole of what makes an
 * incremental run worth having.
 *
 * <p>
 * <b>Old against old is never asked.</b> Not as an optimisation but because the
 * answer cannot have changed: whether two images look alike is a fact about the
 * two of them, and neither was touched. A pair whose verdict could have changed
 * is a pair with a re-fingerprinted file in it, and that file is not old - its
 * relations and its coverage were forgotten, so it arrives here as new.
 *
 * <p>
 * The scan walks the newcomers and, for each, the whole list. Walking every pair
 * and skipping the old ones would be the same n²/2 iterations with a test in
 * them, which is billions of tests to find thousands of pairs.
 */
final class ArrivingPairs {

	private static final int LONGS_PER_HASH = 4;

	private ArrivingPairs() {
	}

	/**
	 * @param hashes every candidate's hash, flattened four words each, in the
	 * order the grouping will index them by
	 * @param newcomer whether the candidate at each position is one of the
	 * arrivals, positionally aligned with {@code hashes}
	 * @param count how many candidates there are
	 * @param arrivals how many of them are arrivals, which is what progress is
	 * reported against: the library is what the scan reads, but the arrivals are
	 * what it is working through
	 * @return the surviving pairs as two ints packed in one long, the lower index
	 * first, in ascending order of the pair - which is the order a full scan would
	 * have produced them in, so the relations index identically
	 */
	static long[] withinRadius(long[] hashes, boolean[] newcomer, int count, int radius, int arrivals,
			SimilarityProgressCallback progress) {
		long[] pairs = new long[1 << 12];
		int found = 0;
		int scanned = 0;

		progress.update(0, arrivals);

		for (int left = 0; left < count; left++) {
			if (!newcomer[left]) {
				continue;
			}

			int base = left * LONGS_PER_HASH;

			long word0 = hashes[base];
			long word1 = hashes[base + 1];
			long word2 = hashes[base + 2];
			long word3 = hashes[base + 3];

			for (int right = 0; right < count; right++) {
				if (ArrivalPairs.alreadyTaken(newcomer, left, right)) {
					continue;
				}

				int other = right * LONGS_PER_HASH;

				int distance = Long.bitCount(word0 ^ hashes[other]) + Long.bitCount(word1 ^ hashes[other + 1])
						+ Long.bitCount(word2 ^ hashes[other + 2]) + Long.bitCount(word3 ^ hashes[other + 3]);

				if (distance <= radius) {
					pairs = ArrivalPairs.withRoomFor(pairs, found);

					pairs[found++] = ArrivalPairs.pack(left, right);
				}
			}

			// Once per arrival rather than once per pair. The callback writes progress
			// to the database and is where a cancellation is noticed, and one arrival is
			// a single pass over the library - milliseconds, so a cancel is still
			// immediate to a person and the writes stay proportional to the arrivals.
			//
			// Reported raw. How the two passes divide a progress bar between them is
			// the caller's arithmetic, and it already belongs to somebody.
			progress.update(++scanned, arrivals);
		}

		return ArrivalPairs.sorted(pairs, found);
	}

}