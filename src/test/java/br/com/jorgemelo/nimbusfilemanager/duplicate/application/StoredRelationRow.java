package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.RelationRow;

/**
 * One stored relation, for tests that need relations without needing a
 * database.
 *
 * <p>
 * A written implementation rather than a mock: the projection is three getters,
 * and stubbing three calls per row would bury the only thing a reader of these
 * tests cares about, which is which pair is approved and at what score.
 */
final class StoredRelationRow implements RelationRow {

	private final long first;
	private final long second;
	private final int similarityPercent;

	private StoredRelationRow(long first, long second, int similarityPercent) {
		this.first = first;
		this.second = second;
		this.similarityPercent = similarityPercent;
	}

	/**
	 * The pair as the database stores it - the lower catalog id first, which the
	 * table's check constraint enforces and the grouping's order depends on.
	 */
	static RelationRow approved(long first, long second, int similarityPercent) {
		return new StoredRelationRow(Math.min(first, second), Math.max(first, second), similarityPercent);
	}

	@Override
	public Long getFirstCatalogFileId() {
		return first;
	}

	@Override
	public Long getSecondCatalogFileId() {
		return second;
	}

	@Override
	public Integer getSimilarityPercent() {
		return similarityPercent;
	}
}