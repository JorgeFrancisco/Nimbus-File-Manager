package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntBiFunction;

/**
 * Complete-linkage clustering from a scorer: each candidate joins the first
 * cluster whose every member scores at or above the threshold, otherwise it
 * starts a new cluster; only clusters with more than one member survive. The
 * pairwise score (0-100, or negative when a cheap pre-filter rejects the pair
 * before the expensive comparison) is supplied by the caller. Media-agnostic: it
 * knows nothing about photos, videos, pHash or SSIM.
 *
 * <p>
 * <b>Two members with different fates, and neither is dead code.</b>
 * {@link #worstScore} is production: both similarity services read a published
 * group's floor with it, over the relations rather than over a scorer.
 * {@link #cluster} is no longer called by production - the analyses group from
 * stored relations through {@link SimilarityRelationGrouper} - and it stays
 * because it is the <em>specification</em> that grouping was proved equivalent
 * to. {@code SimilarityRelationGrouperTest} and
 * {@code VideoRelationGroupingEquivalenceTest} both compare against it, and a
 * greedy order-dependent placement is exactly the kind of behaviour that needs a
 * reference implementation rather than a written-down expectation. Deleting it
 * as unused would delete the oracle.
 */
final class SimilarityCompleteLinkageGrouper {

	private SimilarityCompleteLinkageGrouper() {
	}

	static <T> List<List<T>> cluster(List<T> candidates, int minimum, ToIntBiFunction<T, T> score,
			SimilarityProgressCallback progress) {
		int total = candidates.size();

		int processed = 0;

		progress.update(0, total);

		List<List<T>> clusters = new ArrayList<>();

		for (T candidate : candidates) {
			List<T> target = null;

			for (List<T> cluster : clusters) {
				if (withinThresholdOfAll(candidate, cluster, minimum, score)) {
					target = cluster;

					break;
				}
			}

			if (target == null) {
				target = new ArrayList<>();

				clusters.add(target);
			}

			target.add(candidate);

			progress.update(++processed, total);
		}
		return clusters.stream().filter(cluster -> cluster.size() > 1).toList();
	}

	private static <T> boolean withinThresholdOfAll(T candidate, List<T> cluster, int minimum,
			ToIntBiFunction<T, T> score) {
		for (T member : cluster) {
			if (score.applyAsInt(candidate, member) < minimum) {
				return false;
			}
		}

		return true;
	}

	/** Lowest pairwise score in the group, so the displayed floor is honest. */
	static <T> int worstScore(List<T> group, ToIntBiFunction<T, T> score) {
		int worst = 100;

		for (int first = 0; first < group.size(); first++) {
			for (int second = first + 1; second < group.size(); second++) {
				worst = Math.min(worst, score.applyAsInt(group.get(first), group.get(second)));
			}
		}

		return worst;
	}
}