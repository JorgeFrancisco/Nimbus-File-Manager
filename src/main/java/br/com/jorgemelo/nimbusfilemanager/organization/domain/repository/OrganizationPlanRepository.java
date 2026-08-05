package br.com.jorgemelo.nimbusfilemanager.organization.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import br.com.jorgemelo.nimbusfilemanager.organization.domain.model.OrganizationPlanRecord;

@Repository
public interface OrganizationPlanRepository extends JpaRepository<OrganizationPlanRecord, Long> {

	/**
	 * The plan a screen may show: published, and not past its own expiry.
	 *
	 * <p>
	 * Both conditions are in the query rather than checked afterwards, because
	 * both are the same question - whether this plan is still something to look at
	 * - and answering half of it in Java would leave the other half to a caller
	 * that might forget.
	 */
	@Query("""
			SELECT p
			FROM OrganizationPlanRecord p
			WHERE p.executionId = :executionId
			  AND p.status = br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.PlanStatus.READY
			  AND p.expiresAt > :now
			""")
	Optional<OrganizationPlanRecord> findReadable(@Param("executionId") Long executionId,
			@Param("now") LocalDateTime now);

	/**
	 * Publication: the single update that makes a plan visible.
	 *
	 * <p>
	 * Conditional on the row still being BUILDING, so a second attempt at the same
	 * plan is told it did not publish rather than publishing over what is already
	 * there. Returns how many rows moved, which is that answer.
	 */
	@Modifying
	@Query("""
			UPDATE OrganizationPlanRecord p
			   SET p.status = br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.PlanStatus.READY,
			       p.itemCount = :itemCount, p.conflictCount = :conflictCount,
			       p.plannedMoves = :plannedMoves, p.totalSizeBytes = :totalSizeBytes,
			       p.catalogSignature = :catalogSignature, p.builtAt = :builtAt
			 WHERE p.executionId = :executionId
			   AND p.status = br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.PlanStatus.BUILDING
			""")
	int publish(@Param("executionId") Long executionId, @Param("itemCount") int itemCount,
			@Param("conflictCount") int conflictCount, @Param("plannedMoves") int plannedMoves,
			@Param("totalSizeBytes") long totalSizeBytes, @Param("catalogSignature") String catalogSignature,
			@Param("builtAt") LocalDateTime builtAt);

	@Modifying
	@Query("""
			UPDATE OrganizationPlanRecord p
			   SET p.status = br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.PlanStatus.FAILED
			 WHERE p.executionId = :executionId
			   AND p.status = br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.PlanStatus.BUILDING
			""")
	int markFailed(@Param("executionId") Long executionId);

	/**
	 * What the sweep deletes: plans past their expiry, whatever state they reached.
	 * A plan left BUILDING by a worker that died is covered by the same rule - it
	 * expires like any other, and nothing else ever reads it.
	 */
	List<OrganizationPlanRecord> findByExpiresAtBefore(LocalDateTime cutoff);
}