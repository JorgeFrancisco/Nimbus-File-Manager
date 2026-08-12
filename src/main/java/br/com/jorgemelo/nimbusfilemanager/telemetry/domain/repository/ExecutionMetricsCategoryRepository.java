package br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.model.ExecutionMetricsCategory;

public interface ExecutionMetricsCategoryRepository extends JpaRepository<ExecutionMetricsCategory, Long> {

	List<ExecutionMetricsCategory> findByExecutionIdOrderByCategoryAsc(Long executionId);

	List<ExecutionMetricsCategory> findByExecutionIdInOrderByExecutionIdAscCategoryAsc(Collection<Long> executionIds);

	/**
	 * Clears what a previous attempt of this execution left, so consolidation
	 * replaces rather than appends.
	 *
	 * <p>
	 * A bulk delete rather than a derived one, and the difference is not style:
	 * Hibernate flushes inserts before deletes, so a delete left pending would run
	 * <em>after</em> the rows replacing it and the unique constraint would refuse
	 * the write. This executes where it is written.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("DELETE FROM ExecutionMetricsCategory category WHERE category.executionId = :executionId")
	void deleteByExecutionId(@Param("executionId") Long executionId);
}