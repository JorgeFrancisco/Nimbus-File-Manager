package br.com.jorgemelo.nimbusfilemanager.organization.domain.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import br.com.jorgemelo.nimbusfilemanager.organization.domain.model.OrganizationPlanItemRecord;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.model.OrganizationPlanItemId;

@Repository
public interface OrganizationPlanItemRepository
		extends JpaRepository<OrganizationPlanItemRecord, OrganizationPlanItemId> {

	List<OrganizationPlanItemRecord> findByExecutionIdOrderByOrdinalAsc(Long executionId, Pageable pageable);

	/**
	 * The "only conflicts" page. A separate query rather than a filter over the
	 * previous one because the point is not to read the items that are fine: at a
	 * hundred thousand items, finding the thirty-four conflicts by paging is what
	 * the partial index exists to avoid.
	 */
	@Query("""
			SELECT i
			FROM OrganizationPlanItemRecord i
			WHERE i.executionId = :executionId AND i.conflict = true
			ORDER BY i.ordinal ASC
			""")
	List<OrganizationPlanItemRecord> findConflicts(@Param("executionId") Long executionId, Pageable pageable);

	long countByExecutionIdAndConflictTrue(Long executionId);

	long countByExecutionId(Long executionId);
}