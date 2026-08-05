package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The clustering rule, pinned because its name lied for a long time and an
 * audit read the wrong algorithm out of it.
 *
 * <p>
 * It is complete linkage: a candidate joins a cluster only if it reaches the
 * threshold against <b>every</b> member, not against one of them. Two things
 * follow, and both are relied upon elsewhere - every pair inside a published
 * group was compared and passed, so the published percentage is never below the
 * threshold and the internal "not a match" sentinel can never reach a screen.
 *
 * <p>
 * If anyone ever changes this to true single linkage, these tests fail and the
 * question of what a group's percentage means has to be answered again, on
 * purpose.
 */
class SimilarityCompleteLinkageGrouperTest {

	/**
	 * The case that decides the name. Under single linkage A-B and B-C would drag C
	 * into A's cluster without A and C ever being compared; here C is refused
	 * because it does not reach A.
	 */
	@Test
	void aCandidateThatFailsOneMemberDoesNotJoinTheCluster() {
		Map<String, Integer> scores = Map.of("A|B", 96, "B|C", 96, "A|C", 40);

		List<List<String>> clusters = SimilarityCompleteLinkageGrouper.cluster(List.of("A", "B", "C"), 90,
				(first, second) -> score(scores, first, second), (_, _) -> {
				});

		Assertions.assertThat(clusters).hasSize(1);
		Assertions.assertThat(clusters.getFirst()).containsExactly("A", "B");
	}

	@Test
	void everyPairInsideAGroupReachesTheThreshold() {
		Map<String, Integer> scores = Map.of("A|B", 96, "B|C", 94, "A|C", 92);

		List<List<String>> clusters = SimilarityCompleteLinkageGrouper.cluster(List.of("A", "B", "C"), 90,
				(first, second) -> score(scores, first, second), (_, _) -> {
				});

		Assertions.assertThat(clusters.getFirst()).containsExactly("A", "B", "C");
		Assertions.assertThat(SimilarityCompleteLinkageGrouper.worstScore(clusters.getFirst(),
				(first, second) -> score(scores, first, second))).isGreaterThanOrEqualTo(90);
	}

	/**
	 * The negative pre-filter is what a pair that was never really compared
	 * returns. It is below any valid threshold, so it keeps candidates apart - and
	 * therefore never survives into a group whose percentage a user reads.
	 */
	@Test
	void theNotAMatchSentinelKeepsCandidatesApartInsteadOfReachingAGroup() {
		Map<String, Integer> scores = Map.of("A|B", -1);

		List<List<String>> clusters = SimilarityCompleteLinkageGrouper.cluster(List.of("A", "B"), 70,
				(first, second) -> score(scores, first, second), (_, _) -> {
				});

		Assertions.assertThat(clusters).isEmpty();
	}

	/** A candidate alone is not a group: one file is nobody's duplicate. */
	@Test
	void singleCandidatesAreNotGroups() {
		List<List<String>> clusters = SimilarityCompleteLinkageGrouper.cluster(List.of("A", "B"), 90,
				(_, _) -> 10, (_, _) -> {
				});

		Assertions.assertThat(clusters).isEmpty();
	}

	private int score(Map<String, Integer> scores, String first, String second) {
		return scores.getOrDefault(first.compareTo(second) <= 0 ? first + "|" + second : second + "|" + first, -1);
	}
}