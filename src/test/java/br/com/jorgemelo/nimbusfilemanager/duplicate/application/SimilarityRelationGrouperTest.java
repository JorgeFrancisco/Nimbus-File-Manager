package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The relation-driven grouping has to produce what the scorer-driven one
 * produces - the same groups, the same members, in the same order - because the
 * point of it is speed, and a faster answer to a different question would be no
 * answer at all.
 *
 * <p>
 * Randomised over many shapes rather than over a handful of hand-written cases:
 * the behaviour that has to match is greedy and order-dependent, and the
 * disagreements it can hide are exactly the awkward ones - a candidate that fits
 * two clusters, a cluster that stops fitting once it grows, a relation approved
 * in one direction and asked about in the other.
 */
class SimilarityRelationGrouperTest {

	private static final int NODES = 60;

	/**
	 * Every density from sparse to nearly complete. A sparse graph exercises the
	 * skipping and a dense one exercises the tie-breaking, and only the middle
	 * produces clusters that grow and then reject.
	 */
	@ParameterizedTest
	@ValueSource(ints = { 1, 3, 5, 10, 20, 40, 60, 80 })
	void agreesWithTheScorerDrivenGrouperAtEveryDensity(int percentApproved) {
		for (int seed = 0; seed < 40; seed++) {
			int[][] scores = randomScores(NODES, percentApproved, seed);

			assertThat(relationDriven(scores)).as("density %d%%, seed %d", percentApproved, seed)
					.isEqualTo(scorerDriven(scores));
		}
	}

	/** Nothing approved: every item stands alone and no group survives. */
	@Test
	void agreesWhenNothingIsApproved() {
		int[][] scores = randomScores(NODES, 0, 7);

		assertThat(relationDriven(scores)).isEmpty();
		assertThat(relationDriven(scores)).isEqualTo(scorerDriven(scores));
	}

	/**
	 * Everything approved: one group holding everything, which is the case where
	 * the two implementations could most easily differ - the original walks a
	 * single ever-growing cluster, this one counts its members.
	 */
	@Test
	void agreesWhenEverythingIsApproved() {
		int[][] scores = new int[NODES][NODES];

		for (int first = 0; first < NODES; first++) {
			for (int second = 0; second < NODES; second++) {
				scores[first][second] = 100;
			}
		}

		List<List<Integer>> groups = relationDriven(scores);

		assertThat(groups).hasSize(1);
		assertThat(groups.getFirst()).hasSize(NODES);
		assertThat(groups).isEqualTo(scorerDriven(scores));
	}

	/**
	 * The case complete-linkage exists for: A and C both relate to B but not to
	 * each other, so they may not share a group. A single-linkage rule would put
	 * all three together.
	 */
	@Test
	void refusesToChainThroughAMiddleMemberJustLikeTheOriginal() {
		int[][] scores = new int[3][3];

		approve(scores, 0, 1, 96);
		approve(scores, 1, 2, 96);

		List<List<Integer>> groups = relationDriven(scores);

		assertThat(groups).hasSize(1);
		assertThat(groups.getFirst()).containsExactly(0, 1);
		assertThat(groups).isEqualTo(scorerDriven(scores));
	}

	/**
	 * A candidate that fits two clusters joins the one created first. That is what
	 * "stop at the first match" means in the original, and it is the property most
	 * easily lost when clusters are visited through a map.
	 */
	@Test
	void joinsTheEarliestClusterWhenMoreThanOneFits() {
		int[][] scores = new int[5][5];

		approve(scores, 0, 1, 96);
		approve(scores, 2, 3, 96);
		approve(scores, 4, 0, 96);
		approve(scores, 4, 1, 96);
		approve(scores, 4, 2, 96);
		approve(scores, 4, 3, 96);

		List<List<Integer>> groups = relationDriven(scores);

		assertThat(groups.getFirst()).containsExactly(0, 1, 4);
		assertThat(groups).isEqualTo(scorerDriven(scores));
	}

	private List<List<Integer>> scorerDriven(int[][] scores) {
		List<Integer> candidates = new ArrayList<>();

		for (int index = 0; index < scores.length; index++) {
			candidates.add(index);
		}

		return SimilarityCompleteLinkageGrouper.cluster(candidates, 70, (first, second) -> scores[first][second],
				(_, _) -> {
				});
	}

	private List<List<Integer>> relationDriven(int[][] scores) {
		int[] first = new int[scores.length * scores.length];
		int[] second = new int[first.length];
		int[] pairScores = new int[first.length];
		int count = 0;

		for (int left = 0; left < scores.length; left++) {
			for (int right = left + 1; right < scores.length; right++) {
				if (scores[left][right] >= 70) {
					first[count] = left;
					second[count] = right;
					pairScores[count] = scores[left][right];
					count++;
				}
			}
		}

		return SimilarityRelationGrouper.cluster(scores.length,
				ApprovedRelations.of(first, second, pairScores, count, scores.length), (_, _) -> {
				});
	}

	/**
	 * Scores are symmetric, as the real ones are: SSIM of a pair does not depend
	 * on which of the two was asked about first.
	 */
	private int[][] randomScores(int nodes, int percentApproved, int seed) {
		Random random = new Random(seed * 31L + percentApproved);
		int[][] scores = new int[nodes][nodes];

		for (int first = 0; first < nodes; first++) {
			for (int second = first + 1; second < nodes; second++) {
				approve(scores, first, second, random.nextInt(100) < percentApproved ? 70 + random.nextInt(31) : -1);
			}
		}

		return scores;
	}

	private void approve(int[][] scores, int first, int second, int score) {
		scores[first][second] = score;
		scores[second][first] = score;
	}
}