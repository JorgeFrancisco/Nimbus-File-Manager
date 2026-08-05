package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection;

/**
 * One approved pair as the grouping reads it: two catalog ids and the score.
 *
 * <p>
 * An interface projection rather than the entity, because the grouping wants
 * tens of thousands of these and none of the rest - no generated id, no
 * parameters it already knows, no timestamp. Reading entities here would be
 * three objects and a persistence context per relation for three numbers.
 */
public interface RelationRow {

	Long getFirstCatalogFileId();

	Long getSecondCatalogFileId();

	Integer getSimilarityPercent();
}