package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.Arrays;
import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRawResponse;

/**
 * Compares every pair once, in two passes, and keeps the ones that pass.
 *
 * <p>
 * The grouping used to do this itself, lazily, from inside its own loop - and
 * because a candidate is offered to every cluster in turn, it asked about most
 * pairs again and again: 6,37 billion calls for 7,18 billion possible pairs, and
 * 351 seconds over the whole library.
 *
 * <p>
 * The two passes exist for the same reason the first version was slower than it
 * had to be. Distances need 32 bytes per photo and SSIM needs 1024, so a single
 * loop doing both alternates between a 3,7 MB array and a 120 MB one, and the
 * caches never settle. Separating them lets the distance scan run over a working
 * set that stays resident, and lets SSIM touch the samples only for the pairs
 * that survived - which is a small fraction of one percent.
 *
 * <p>
 * The two filters are the production ones and in the production order: distance
 * first, SSIM only inside the radius. Nothing here decides what is similar - it
 * computes the same answers earlier, so the grouping can read them instead of
 * asking for them.
 *
 * <p>
 * Only approvals are kept. A pair that fails is not stored, because the grouping
 * cannot tell the difference: outside the radius, scored below the threshold and
 * never compared are one answer to it.
 */
final class PhotoRelationBuilder {

	private static final int LONGS_PER_HASH = 4;

	/**
	 * How the progress bar is split between the two passes. Taken from the measured
	 * shares rather than chosen: the distance scan is the long one, and reporting
	 * an even split would stall the bar at the handover.
	 */
	private static final int DISTANCE_SHARE = 80;

	/**
	 * Rows between progress reports during the distance scan.
	 *
	 * <p>
	 * The callback both moves the bar and is where a cancellation is noticed, and
	 * it writes to the database - so reporting every row, as this used to, is over
	 * a hundred thousand writes for a bar nobody can read that fast. At the
	 * measured rate a row is a fifth of a millisecond, so this batch is under
	 * thirty and a cancel is still immediate to a person.
	 */
	private static final int ROWS_PER_REPORT = 128;

	private final LuminanceSsimService luminanceSsimService;
	private final int radius;

	PhotoRelationBuilder(LuminanceSsimService luminanceSsimService, int radius) {
		this.luminanceSsimService = luminanceSsimService;
		this.radius = radius;
	}

	BuiltRelations build(List<PhotoHashRawResponse> candidates, int minimum, SimilarityProgressCallback progress) {
		int count = candidates.size();

		long[] pairs = withinRadius(pack(candidates), count, radius, progress);

		return approve(candidates, pairs, minimum, progress);
	}

	/**
	 * Hashes flattened into one array, four words each, so the scan reads memory
	 * in order instead of chasing a pointer per photo.
	 */
	static long[] pack(List<PhotoHashRawResponse> candidates) {
		long[] packed = new long[candidates.size() * LONGS_PER_HASH];

		for (int index = 0; index < candidates.size(); index++) {
			pack(candidates.get(index).phash(), packed, index);
		}

		return packed;
	}

	/**
	 * One hash into the four words that represent it, at its position in a flat
	 * array. Apart from the loop above because an incremental run assembles the
	 * same array from rows that carry the hash alone, and the bit order the whole
	 * distance scan depends on must have exactly one definition.
	 */
	static void pack(byte[] hash, long[] into, int index) {
		for (int word = 0; word < LONGS_PER_HASH; word++) {
			long value = 0;

			for (int position = 0; position < 8; position++) {
				value = (value << 8) | (hash[word * 8 + position] & 0xFFL);
			}

			into[index * LONGS_PER_HASH + word] = value;
		}
	}

	/**
	 * First pass: every pair, distance only. The survivors are returned as packed
	 * indices - two ints in one long - because at over a million pairs an object
	 * each would cost more than the scan does.
	 *
	 * <p>
	 * Sequential on purpose. Splitting the rows across four threads made the scan
	 * itself three times faster in isolation, but measured through the whole
	 * production path it finished in 37,6 s against 37 - the surrounding work and
	 * the memory pressure absorbed the gain. It cost per-thread buffers, a shared
	 * counter and a sort of over a million elements to keep the order the greedy
	 * grouping depends on, which is a lot of machinery to buy nothing.
	 */
	static long[] withinRadius(long[] hashes, int count, int radius, SimilarityProgressCallback progress) {
		progress.update(0, count);

		long[] pairs = new long[1 << 16];
		int found = 0;

		for (int left = 0; left < count; left++) {
			int base = left * LONGS_PER_HASH;

			long word0 = hashes[base];
			long word1 = hashes[base + 1];
			long word2 = hashes[base + 2];
			long word3 = hashes[base + 3];

			for (int right = left + 1; right < count; right++) {
				int other = right * LONGS_PER_HASH;

				int distance = Long.bitCount(word0 ^ hashes[other]) + Long.bitCount(word1 ^ hashes[other + 1])
						+ Long.bitCount(word2 ^ hashes[other + 2]) + Long.bitCount(word3 ^ hashes[other + 3]);

				if (distance <= radius) {
					if (found == pairs.length) {
						pairs = Arrays.copyOf(pairs, found * 2);
					}

					pairs[found++] = ((long) left << 32) | right;
				}
			}

			report(left + 1, count, progress);
		}

		return Arrays.copyOf(pairs, found);
	}

	/**
	 * Batched, because the callback both moves the bar and writes to the database:
	 * one report per row was over a hundred thousand writes for a bar nobody reads
	 * that fast.
	 */
	private static void report(int rowsDone, int count, SimilarityProgressCallback progress) {
		if (rowsDone % ROWS_PER_REPORT == 0 || rowsDone == count) {
			reportDistance(rowsDone, count, progress);
		}
	}

	/**
	 * The distance pass's share of the bar, applied to a count of rows.
	 *
	 * <p>
	 * Exposed as a method rather than as the number it uses, because an
	 * incremental run reports its own distance pass and then hands the SSIM pass
	 * to {@link #approve}, which continues from where this leaves off. The two
	 * have to agree, and a split that lives in one place cannot disagree with
	 * itself. Unbatched: the caller decides how often a row is worth reporting,
	 * and one arrival scanned against the whole library is not the same unit as
	 * one row of a full scan.
	 */
	static void reportDistance(int rowsDone, int count, SimilarityProgressCallback progress) {
		progress.update(rowsDone * DISTANCE_SHARE / 100, count);
	}

	/**
	 * Second pass: SSIM over the survivors, and only over them. The samples are
	 * touched here for the first time, when the set of pairs that need them is
	 * already known and small.
	 */
	BuiltRelations approve(List<PhotoHashRawResponse> candidates, long[] pairs, int minimum,
			SimilarityProgressCallback progress) {
		byte[][] luminance = new byte[candidates.size()][];

		for (int index = 0; index < candidates.size(); index++) {
			luminance[index] = candidates.get(index).luminance();
		}

		return approve(luminance, pairs, minimum, candidates.size(), progress);
	}

	/**
	 * The same pass over samples the caller already has in hand.
	 *
	 * <p>
	 * An incremental run does not hold a row per candidate: it reads the hashes of
	 * the whole library and the samples of only the photos a pair survived to
	 * need, so what it can offer is an array with holes in it - which is what a
	 * missing sample has always looked like here.
	 *
	 * @param luminance one sample per candidate position, {@code null} where the
	 * sample was not loaded or does not exist
	 * @param reported what the progress bar counts, which is not always the number
	 * of candidates: an arrival is measured against the library but is working
	 * through its arrivals
	 */
	BuiltRelations approve(byte[][] luminance, long[] pairs, int minimum, int reported,
			SimilarityProgressCallback progress) {
		RelationAccumulator approved = new RelationAccumulator();

		for (int index = 0; index < pairs.length; index++) {
			int left = (int) (pairs[index] >>> 32);
			int right = (int) pairs[index];

			if (luminance[left] == null || luminance[right] == null) {
				continue;
			}

			int score = luminanceSsimService.similarityPercent(luminance[left], luminance[right]);

			if (score >= minimum) {
				approved.approve(left, right, score);
			}

			reportSsim(progress, index, pairs.length, reported);
		}

		return approved.toRelations(luminance.length);
	}

	/**
	 * Reported per thousand pairs rather than per pair: the callback writes
	 * progress to the database, and a million writes would cost more than the SSIM
	 * they are reporting on. A thousand pairs is a few milliseconds, so a
	 * cancellation is still noticed at once.
	 */
	private void reportSsim(SimilarityProgressCallback progress, int index, int total, int count) {
		if (index % 1000 == 0 || index == total - 1) {
			int done = DISTANCE_SHARE + (100 - DISTANCE_SHARE) * (index + 1) / Math.max(1, total);

			progress.update(count * done / 100, count);
		}
	}

}