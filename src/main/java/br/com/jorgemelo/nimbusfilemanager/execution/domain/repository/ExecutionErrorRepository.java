package br.com.jorgemelo.nimbusfilemanager.execution.domain.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.model.ExecutionError;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.projection.ExecutionErrorSummaryResponse;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.repository.projection.ErrorFileDetailsResponse;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.projection.ErrorStatisticsResponse;

public interface ExecutionErrorRepository extends JpaRepository<ExecutionError, Long> {

	List<ExecutionError> findByExecutionIdOrderByCreatedAtAsc(Long executionId);

	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.projection.ExecutionErrorSummaryResponse(
				CAST(e.errorType AS string),
				COUNT(e)
			)
			FROM ExecutionError e
			WHERE e.execution.id = :executionId
			GROUP BY e.errorType
			ORDER BY COUNT(e) DESC
			""")
	List<ExecutionErrorSummaryResponse> summarizeByExecutionId(Long executionId);

	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.projection.ErrorStatisticsResponse(
				CAST(e.errorType AS string),
				COUNT(e)
			)
			FROM ExecutionError e
			GROUP BY e.errorType
			ORDER BY COUNT(e) DESC
			""")
	List<ErrorStatisticsResponse> summarize();

	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.projection.ErrorStatisticsResponse(
				CAST(e.errorType AS string),
				COUNT(DISTINCT e.path)
			)
			FROM ExecutionError e
			GROUP BY e.errorType
			ORDER BY COUNT(DISTINCT e.path) DESC
			""")
	List<ErrorStatisticsResponse> summarizeDistinctFiles();

	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.inventory.domain.repository.projection.ErrorFileDetailsResponse(
				e.path,
				CAST(e.errorType AS string),
				COUNT(e),
				MIN(e.createdAt),
				MAX(e.createdAt)
			)
			FROM ExecutionError e
			WHERE (:errorType IS NULL OR e.errorType = :errorType)
			  AND (:path IS NULL OR LOWER(e.path) LIKE LOWER(CONCAT('%', :path, '%')))
			GROUP BY e.path, e.errorType
			ORDER BY COUNT(e) DESC, MAX(e.createdAt) DESC
			""")
	Page<ErrorFileDetailsResponse> findErrorFileDetails(ExecutionErrorType errorType, String path, Pageable pageable);
}