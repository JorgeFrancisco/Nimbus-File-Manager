package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Whether a small change to the library can be applied to an existing result
 * instead of recomputing the whole thing.
 *
 * <p>
 * The grouping is greedy: candidates are visited in a fixed order and each joins
 * the first cluster whose every member it relates to. Two consequences follow,
 * and they point in opposite directions - which is the reason this exists.
 *
 * <p>
 * <b>Adding is equivalent.</b> The loop never revisits a decision, so the state
 * after visiting the first n candidates depends on those n alone. A photo whose
 * id is higher than every existing one is visited last, and the run that
 * includes it does exactly what the run without it did, then places it. Since the
 * order is {@code catalog_file.id ASC} and an incoming photo always gets a
 * higher id, that is the ordinary case rather than a lucky one.
 *
 * <p>
 * <b>Removing is not.</b> A member can be the reason another candidate was
 * refused, and taking it away would have let that candidate in - which is a
 * decision made earlier in the order and cannot be revised locally. The test
 * below is a counterexample, not a corner case, and it is the whole reason
 * removal has to be treated differently.
 *
 * <p>
 * Synthetic, deterministic and fast on purpose: none of this needs a real
 * library, and a test that did would be a benchmark pretending to be a proof.
 */
class SimilarityIncrementalEquivalenceTest {

	private static final int MINIMUM = 70;

	/**
	 * Adding a candidate at the end of the order gives the same answer as
	 * recomputing everything with it included - at every density, over many shapes.
	 */
	@ParameterizedTest
	@ValueSource(ints = { 2, 5, 10, 20, 40, 70 })
	void addingAtTheEndOfTheOrderMatchesAFullRebuild(int percentApproved) {
		for (int seed = 0; seed < 60; seed++) {
			int nodes = 25;
			int[][] scores = randomScores(nodes, percentApproved, seed);

			List<List<Integer>> rebuilt = group(scores, nodes);
			List<List<Integer>> incremental = addLast(scores, nodes);

			assertThat(incremental).as("density %d%%, seed %d", percentApproved, seed).isEqualTo(rebuilt);
		}
	}

	/** The new photo relates to nothing: no group appears, none changes. */
	@Test
	void aPhotoRelatedToNothingChangesNothing() {
		int[][] scores = new int[4][4];

		approve(scores, 0, 1, 96);

		assertThat(addLast(scores, 4)).isEqualTo(group(scores, 4));
	}

	/**
	 * The new photo pairs with one that was alone. This is the case the persisted
	 * result cannot answer by itself: groups of one are not stored, so an
	 * incremental update has to know about the files that belong to no group.
	 */
	@Test
	void aPhotoPairingWithAFileThatWasAloneFormsANewGroup() {
		int[][] scores = new int[3][3];

		approve(scores, 0, 2, 96);

		List<List<Integer>> groups = group(scores, 3);

		assertThat(groups).hasSize(1);
		assertThat(groups.getFirst()).containsExactly(0, 2);
		assertThat(addLast(scores, 3)).isEqualTo(groups);
	}

	/** The new photo relates to every member of an existing group, so it joins. */
	@Test
	void aPhotoRelatedToEveryMemberJoinsTheGroup() {
		int[][] scores = new int[3][3];

		approve(scores, 0, 1, 96);
		approve(scores, 0, 2, 96);
		approve(scores, 1, 2, 96);

		assertThat(addLast(scores, 3)).isEqualTo(group(scores, 3));
		assertThat(group(scores, 3).getFirst()).containsExactly(0, 1, 2);
	}

	/**
	 * Related to some members but not all: complete-linkage refuses, and the photo
	 * starts a group of its own instead of weakening an existing one.
	 */
	@Test
	void aPhotoRelatedToSomeMembersButNotAllStartsItsOwn() {
		int[][] scores = new int[3][3];

		approve(scores, 0, 1, 96);
		approve(scores, 0, 2, 96);

		List<List<Integer>> groups = group(scores, 3);

		assertThat(groups).hasSize(1);
		assertThat(groups.getFirst()).containsExactly(0, 1);
		assertThat(addLast(scores, 3)).isEqualTo(groups);
	}

	/** Fits two groups: joins the one created first, incrementally too. */
	@Test
	void aPhotoThatFitsTwoGroupsJoinsTheEarliest() {
		int[][] scores = new int[5][5];

		approve(scores, 0, 1, 96);
		approve(scores, 2, 3, 96);
		approve(scores, 4, 0, 96);
		approve(scores, 4, 1, 96);
		approve(scores, 4, 2, 96);
		approve(scores, 4, 3, 96);

		assertThat(addLast(scores, 5)).isEqualTo(group(scores, 5));
		assertThat(group(scores, 5).getFirst()).containsExactly(0, 1, 4);
	}

	/**
	 * <b>The counterexample.</b> A, B, C in order, with A related to both and B and
	 * C unrelated to each other. B joins A; C is refused because of B. Remove B and
	 * a rebuild puts A and C together - a group the incremental view cannot reach,
	 * because C's refusal happened before the removal and greedy decisions are
	 * never revisited.
	 *
	 * <p>
	 * This is why removal cannot be applied to a published result: the answer
	 * depends on decisions taken earlier in the order.
	 */
	@Test
	void removingAMemberCanCreateAGroupThatNoLocalUpdateWouldFind() {
		int[][] scores = new int[3][3];

		approve(scores, 0, 1, 96);
		approve(scores, 0, 2, 96);

		assertThat(group(scores, 3).getFirst()).as("before the removal").containsExactly(0, 1);

		List<List<Integer>> localUpdate = withoutMember(group(scores, 3), 1);
		List<List<Integer>> rebuilt = group(without(scores, 1), 3);

		assertThat(localUpdate).as("dropping the member locally leaves nothing").isEmpty();
		assertThat(rebuilt).as("a rebuild finds a group the local update cannot").hasSize(1);
		assertThat(rebuilt.getFirst()).containsExactly(0, 2);
		assertThat(localUpdate).isNotEqualTo(rebuilt);
	}

	/**
	 * Removal is equivalent when nothing was ever refused because of the removed
	 * file - here a plain pair, whose disappearance leaves no group and no missed
	 * opportunity. Worth stating because it shows the difficulty is specific, not
	 * universal.
	 */
	@Test
	void removingFromAPlainPairIsEquivalent() {
		int[][] scores = new int[2][2];

		approve(scores, 0, 1, 96);

		assertThat(withoutMember(group(scores, 2), 1)).isEqualTo(group(without(scores, 1), 2));
	}

	/**
	 * And the cheap alternative for removal: the relations do not change when a file
	 * goes away, so re-running only the grouping over the surviving relations gives
	 * the rebuild's answer without recomputing a single distance or SSIM.
	 *
	 * <p>
	 * Compared against a rebuild that never saw the file - the matrix compacted and
	 * the survivors renumbered - because that is the claim. An earlier version of
	 * this test compared the survivors' grouping with itself, which passes for
	 * every possible implementation and therefore says nothing.
	 *
	 * <p>
	 * The two agreeing is also what licenses loading only the files that take part
	 * in a relation: the departed one is exactly a node with no neighbours, and the
	 * equality says such a node changes no group.
	 */
	@Test
	void regroupingTheSurvivingRelationsMatchesARebuildThatNeverSawTheFile() {
		for (int seed = 0; seed < 60; seed++) {
			int nodes = 25;
			int[][] scores = randomScores(nodes, 20, seed);

			for (int removed : new int[] { 0, 7, 13, nodes - 1 }) {
				assertThat(group(without(scores, removed), nodes)).as("seed %d, removed %d", seed, removed)
						.isEqualTo(rebuiltWithout(scores, nodes, removed));
			}
		}
	}

	/**
	 * The grouping of a library the removed file was never part of: its row and
	 * column dropped, one fewer node, and the resulting positions translated back
	 * so that the two answers are about the same files.
	 */
	private List<List<Integer>> rebuiltWithout(int[][] scores, int nodes, int removed) {
		int[] original = new int[nodes - 1];
		int[][] compacted = new int[nodes - 1][nodes - 1];
		int firstIndex = 0;

		for (int first = 0; first < nodes; first++) {
			if (first == removed) {
				continue;
			}

			original[firstIndex] = first;

			int secondIndex = 0;

			for (int second = 0; second < nodes; second++) {
				if (second != removed) {
					compacted[firstIndex][secondIndex++] = scores[first][second];
				}
			}

			firstIndex++;
		}

		return group(compacted, nodes - 1).stream()
				.map(cluster -> cluster.stream().map(member -> original[member]).toList()).toList();
	}

	/**
	 * The full grouping over a score matrix, which is what a rebuild produces.
	 */
	private List<List<Integer>> group(int[][] scores, int nodes) {
		return SimilarityRelationGrouper.cluster(nodes, relationsOf(scores, nodes), (_, _) -> {
		});
	}

	/**
	 * The incremental placement: group everything except the last candidate, then
	 * place it into the earliest cluster whose every member it relates to -
	 * counting clusters of one, which a published result does not store.
	 */
	private List<List<Integer>> addLast(int[][] scores, int nodes) {
		int newcomer = nodes - 1;

		List<List<Integer>> clusters = allClusters(scores, newcomer);
		int target = -1;

		for (int index = 0; index < clusters.size() && target < 0; index++) {
			if (clusters.get(index).stream().allMatch(member -> scores[newcomer][member] >= MINIMUM)) {
				target = index;
			}
		}

		if (target < 0) {
			clusters.add(new ArrayList<>(List.of(newcomer)));
		} else {
			clusters.get(target).add(newcomer);
		}

		return clusters.stream().filter(cluster -> cluster.size() > 1).toList();
	}

	/**
	 * Every cluster of the previous state, singletons included and in creation
	 * order. The grouper drops singletons because they are not results; an
	 * incremental update needs them, because a newcomer pairing with a lone file is
	 * exactly how a new group appears.
	 */
	private List<List<Integer>> allClusters(int[][] scores, int nodes) {
		List<List<Integer>> clusters = new ArrayList<>();

		for (int candidate = 0; candidate < nodes; candidate++) {
			int[] row = scores[candidate];
			int target = -1;

			for (int index = 0; index < clusters.size() && target < 0; index++) {
				if (clusters.get(index).stream().allMatch(member -> row[member] >= MINIMUM)) {
					target = index;
				}
			}

			if (target < 0) {
				clusters.add(new ArrayList<>(List.of(candidate)));
			} else {
				clusters.get(target).add(candidate);
			}
		}

		return clusters;
	}

	private List<List<Integer>> withoutMember(List<List<Integer>> groups, int removed) {
		return groups.stream().map(group -> group.stream().filter(member -> member != removed).toList())
				.filter(group -> group.size() > 1).toList();
	}

	/** The same matrix with one file's relations gone, as a deletion leaves it. */
	private int[][] without(int[][] scores, int removed) {
		int[][] copy = new int[scores.length][scores.length];

		for (int first = 0; first < scores.length; first++) {
			for (int second = 0; second < scores.length; second++) {
				copy[first][second] = first == removed || second == removed ? 0 : scores[first][second];
			}
		}

		return copy;
	}

	private ApprovedRelations relationsOf(int[][] scores, int nodes) {
		int[] first = new int[nodes * nodes];
		int[] second = new int[first.length];
		int[] pairScores = new int[first.length];
		int count = 0;

		for (int left = 0; left < nodes; left++) {
			for (int right = left + 1; right < nodes; right++) {
				if (scores[left][right] >= MINIMUM) {
					first[count] = left;
					second[count] = right;
					pairScores[count] = scores[left][right];
					count++;
				}
			}
		}

		return ApprovedRelations.of(first, second, pairScores, count, nodes);
	}

	private int[][] randomScores(int nodes, int percentApproved, int seed) {
		Random random = new Random(seed * 131L + percentApproved);
		int[][] scores = new int[nodes][nodes];

		for (int first = 0; first < nodes; first++) {
			for (int second = first + 1; second < nodes; second++) {
				approve(scores, first, second,
						random.nextInt(100) < percentApproved ? MINIMUM + random.nextInt(31) : 0);
			}
		}

		return scores;
	}

	private void approve(int[][] scores, int first, int second, int score) {
		scores[first][second] = score;
		scores[second][first] = score;
	}
}