package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityRelation;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.RelationRow;

public interface SimilarityRelationRepository extends JpaRepository<SimilarityRelation, Long> {

	/**
	 * Every approved relation of one parameter set whose two files are both still
	 * eligible - which is the whole input the grouping needs.
	 *
	 * <p>
	 * Eligibility arrives as the list of ids that
	 * {@code MediaFingerprintRepository.findEligibleForSimilarity} chose, rather
	 * than being re-derived here. That predicate is subtle - the folder exclusion
	 * over Windows paths has already cost one production defect - and it lives in
	 * exactly one place. Passing its answer keeps it there and keeps the two sides
	 * agreeing by construction.
	 *
	 * <p>
	 * Both sides are filtered because a relation is only usable when both files are
	 * in the analysis: excluding one file has to remove its relations from the
	 * grouping without deleting them, so that lifting the exclusion later reuses
	 * what was already computed instead of recomputing it.
	 *
	 * <p>
	 * Ordered by the pair, so the caller reads relations in the same
	 * {@code catalog_file.id} order the grouping visits candidates in - the greedy
	 * placement depends on that order, and letting the physical row order decide it
	 * would make the result depend on how PostgreSQL happened to store them.
	 */
	@Query(value = """
			SELECT r.first_catalog_file_id AS firstCatalogFileId,
			       r.second_catalog_file_id AS secondCatalogFileId,
			       r.similarity_percent AS similarityPercent
			FROM similarity_relation r
			WHERE r.algorithm_id = :algorithm
			  AND r.max_distance = :maxDistance
			  AND r.min_similarity = :minSimilarity
			  AND r.relation_digest = :relationDigest
			  AND r.first_catalog_file_id = ANY(:eligible)
			  AND r.second_catalog_file_id = ANY(:eligible)
			ORDER BY r.first_catalog_file_id, r.second_catalog_file_id
			""", nativeQuery = true)
	List<RelationRow> findEligibleRelations(@Param("algorithm") String algorithm,
			@Param("maxDistance") int maxDistance, @Param("minSimilarity") int minSimilarity,
			@Param("relationDigest") String relationDigest, @Param("eligible") Long[] eligible);

	/**
	 * The files an incremental run has to compare - the eligible ones that are not
	 * yet part of this family's relation universe.
	 *
	 * <p>
	 * Ordered by catalog id because that is the order the grouping visits
	 * candidates in, and because a run that has to stop half way should have done
	 * the oldest first, so the next one continues rather than restarts.
	 */
	@Query(value = """
			SELECT eligible.id
			FROM unnest(:eligible) AS eligible(id)
			WHERE NOT EXISTS (SELECT 1 FROM similarity_relation_coverage c
			                  WHERE c.algorithm_id = :algorithm
			                    AND c.max_distance = :maxDistance
			                    AND c.min_similarity = :minSimilarity
			                    AND c.relation_digest = :relationDigest
			                    AND c.catalog_file_id = eligible.id)
			ORDER BY eligible.id
			""", nativeQuery = true)
	List<Long> findEligibleNotCovered(@Param("algorithm") String algorithm, @Param("maxDistance") int maxDistance,
			@Param("minSimilarity") int minSimilarity, @Param("relationDigest") String relationDigest,
			@Param("eligible") Long[] eligible);

	/**
	 * Every file already incorporated into this family, which is what an
	 * incremental run compares the newcomers against.
	 *
	 * <p>
	 * The covered set and not the eligible set, and the difference is a
	 * correctness one rather than a preference. A file that is covered but hidden
	 * today is still part of the universe: skipping it would leave the pair
	 * between it and the newcomer evaluated by nobody, and once both are marked
	 * covered no later run would ever look at it.
	 * {@code SimilarityCoverageModelTest} holds that counterexample.
	 */
	@Query(value = """
			SELECT c.catalog_file_id
			FROM similarity_relation_coverage c
			WHERE c.algorithm_id = :algorithm
			  AND c.max_distance = :maxDistance
			  AND c.min_similarity = :minSimilarity
			  AND c.relation_digest = :relationDigest
			ORDER BY c.catalog_file_id
			""", nativeQuery = true)
	List<Long> findCovered(@Param("algorithm") String algorithm, @Param("maxDistance") int maxDistance,
			@Param("minSimilarity") int minSimilarity, @Param("relationDigest") String relationDigest);

	/**
	 * The thresholds this parameter set has coverage under - that is, the families
	 * somebody has actually analysed.
	 *
	 * <p>
	 * Asked when files arrive, to find out which published answers have fallen
	 * behind. The threshold is a per-user screen preference and therefore not
	 * readable from any single setting; the families that exist are, and they are
	 * the only ones an arrival can bring up to date.
	 *
	 * <p>
	 * Scoped by the medium's digest as well, so a video analysed under one quorum
	 * does not name a threshold for a run that would compare under another - the
	 * arrival would compute relations for a family whose coverage it then could
	 * not honour.
	 */
	@Query(value = """
			SELECT DISTINCT c.min_similarity
			FROM similarity_relation_coverage c
			WHERE c.algorithm_id = :algorithm AND c.max_distance = :maxDistance
			  AND c.relation_digest = :relationDigest
			ORDER BY c.min_similarity
			""", nativeQuery = true)
	List<Integer> findAnalysedThresholds(@Param("algorithm") String algorithm, @Param("maxDistance") int maxDistance,
			@Param("relationDigest") String relationDigest);

}