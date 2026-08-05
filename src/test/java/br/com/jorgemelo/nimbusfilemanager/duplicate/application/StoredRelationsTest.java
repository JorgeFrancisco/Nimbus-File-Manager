package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Turning stored rows back into the structure the grouping consults.
 *
 * <p>
 * The whole risk here is silent: a relation indexed against the wrong position
 * still groups, still produces plausible output, and groups the wrong photos.
 * So what is pinned is the correspondence itself - which file is at which
 * position, and what happens to a relation whose file is not there.
 */
class StoredRelationsTest {

	@Test
	void namesEveryFileThatTakesPartOnceAndInCatalogOrder() {
		StoredRelations stored = StoredRelations.of(List.of(StoredRelationRow.approved(7L, 9L, 96),
				StoredRelationRow.approved(3L, 9L, 95), StoredRelationRow.approved(3L, 7L, 97)));

		assertThat(stored.participants()).containsExactly(3L, 7L, 9L);
	}

	@Test
	void namesNobodyWhenNothingWasApproved() {
		assertThat(StoredRelations.of(List.of()).participants()).isEmpty();
	}

	/**
	 * The positions come from the candidate list, not from the stored ids: catalog
	 * ids are sparse, and treating them as indices is how a grouping ends up
	 * describing files it never loaded.
	 */
	@Test
	void positionsFollowTheCandidateListRatherThanTheStoredIds() {
		StoredRelations stored = StoredRelations.of(List.of(StoredRelationRow.approved(10L, 30L, 96)));

		ApprovedRelations relations = stored.indexedBy(new long[] { 10L, 20L, 30L });

		assertThat(relations.approved(0, 2)).isTrue();
		assertThat(relations.scoreOf(0, 2)).isEqualTo(96);
		assertThat(relations.degree(1)).as("the file in between takes part in nothing").isZero();
	}

	/**
	 * A relation is only usable when both of its files are in the analysis, so one
	 * naming a file the caller did not load is dropped - which is what happens
	 * when the user hides a file between the request and the run.
	 */
	@Test
	void aRelationNamingAFileThatIsNotACandidateIsDropped() {
		StoredRelations stored = StoredRelations.of(
				List.of(StoredRelationRow.approved(3L, 7L, 96), StoredRelationRow.approved(3L, 9L, 95)));

		ApprovedRelations relations = stored.indexedBy(new long[] { 3L, 7L });

		assertThat(relations.approved(0, 1)).isTrue();
		assertThat(relations.degree(0)).as("only the relation whose two files are both candidates").isEqualTo(1);
	}

	/** Both directions answer the same, which the grouping relies on. */
	@Test
	void aRelationIsReadableFromEitherSide() {
		ApprovedRelations relations = StoredRelations.of(List.of(StoredRelationRow.approved(5L, 8L, 91)))
				.indexedBy(new long[] { 5L, 8L });

		assertThat(relations.scoreOf(0, 1)).isEqualTo(91);
		assertThat(relations.scoreOf(1, 0)).isEqualTo(91);
	}
}