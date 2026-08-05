package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The adjacency the grouping consults, and the two things it has to get right.
 *
 * <p>
 * The first is that an absent pair answers as clearly as a present one: the
 * grouping asks a single question, and outside the radius, scored below the
 * threshold and never compared all have to come back the same way. The second is
 * that a node's neighbours are sorted so lookups can bisect them - and that the
 * scores stay beside the neighbours they belong to while that happens, which is
 * the failure nothing downstream could notice.
 */
class ApprovedRelationsTest {

	@Test
	void anApprovedPairAnswersWithItsScoreFromEitherSide() {
		ApprovedRelations relations = ApprovedRelations.of(new int[] { 0 }, new int[] { 2 }, new int[] { 96 }, 1, 3);

		assertThat(relations.approved(0, 2)).isTrue();
		assertThat(relations.scoreOf(0, 2)).isEqualTo(96);
		assertThat(relations.scoreOf(2, 0)).isEqualTo(96);
	}

	/**
	 * A pair nobody approved scores negative rather than zero, because zero is a
	 * legitimate SSIM and a caller comparing against a threshold has to be able to
	 * tell "scored badly" from "not there".
	 */
	@Test
	void aPairThatWasNotApprovedScoresNegativeAndIsNotApproved() {
		ApprovedRelations relations = ApprovedRelations.of(new int[] { 0 }, new int[] { 1 }, new int[] { 96 }, 1, 3);

		assertThat(relations.approved(0, 2)).isFalse();
		assertThat(relations.scoreOf(0, 2)).isNegative();
		assertThat(relations.degree(2)).isZero();
	}

	/**
	 * Neighbours arriving in descending order still come out ascending, each with
	 * the score it arrived with. The sort moves two parallel arrays, so a pair that
	 * kept its position while its score did not would hand the grouping somebody
	 * else's percentage - and every group would still look plausible.
	 */
	@Test
	void neighboursAreSortedAndKeepTheirOwnScores() {
		ApprovedRelations relations = ApprovedRelations.of(new int[] { 0, 0, 0 }, new int[] { 3, 2, 1 },
				new int[] { 93, 95, 97 }, 3, 4);

		assertThat(relations.degree(0)).isEqualTo(3);
		assertThat(relations.neighbourAt(0, 0)).isEqualTo(1);
		assertThat(relations.neighbourAt(0, 1)).isEqualTo(2);
		assertThat(relations.neighbourAt(0, 2)).isEqualTo(3);

		assertThat(relations.scoreOf(0, 1)).isEqualTo(97);
		assertThat(relations.scoreOf(0, 2)).isEqualTo(95);
		assertThat(relations.scoreOf(0, 3)).isEqualTo(93);
	}

	/**
	 * The footprint is three int arrays and no objects, which is the claim the
	 * class was written to make: the map of boxed pairs it replaced measured 68 MB
	 * on the library where this measures a few.
	 */
	@Test
	void theFootprintIsThreeIntArraysAndNothingElse() {
		ApprovedRelations relations = ApprovedRelations.of(new int[] { 0 }, new int[] { 1 }, new int[] { 96 }, 1, 3);

		// 4 offsets for 3 nodes, and 2 neighbours and 2 scores for the one relation
		// stored from both sides.
		assertThat(relations.bytes()).isEqualTo(8L * Integer.BYTES);
	}

	@Test
	void aStructureWithNoRelationsHasNoNeighbours() {
		ApprovedRelations relations = ApprovedRelations.of(new int[0], new int[0], new int[0], 0, 2);

		assertThat(relations.degree(0)).isZero();
		assertThat(relations.approved(0, 1)).isFalse();
		assertThat(relations.bytes()).isEqualTo(3L * Integer.BYTES);
	}
}