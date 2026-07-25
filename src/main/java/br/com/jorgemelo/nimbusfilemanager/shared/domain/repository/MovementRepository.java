package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.MovementSummaryResponse;

public interface MovementRepository extends JpaRepository<Movement, Long> {

	@EntityGraph(attributePaths = { "execution", "catalogFile" })
	List<Movement> findByExecutionIdAndStatusInOrderByIdDesc(Long executionId, Collection<MovementStatus> statuses);

	@EntityGraph(attributePaths = { "execution", "catalogFile" })
	List<Movement> findByExecutionIdOrderByIdAsc(Long executionId);

	/**
	 * Files still sitting in quarantine (a soft-deleted duplicate that has not been
	 * restored yet): status {@code MOVED} and reason {@code DUPLICATE_QUARANTINED}.
	 * Drives the Quarentena screen.
	 */
	@EntityGraph(attributePaths = { "execution", "catalogFile" })
	Page<Movement> findByStatusAndReasonOrderByIdDesc(MovementStatus status, MovementReason reason, Pageable pageable);

	@EntityGraph(attributePaths = { "execution", "catalogFile" })
	Optional<Movement> findByPublicId(UUID publicId);

	/**
	 * Still-quarantined files whose soft-delete happened before {@code cutoff}: the
	 * retention purge uses this to find what is old enough to expunge. Oldest first
	 * so a capped run always tackles the most overdue items.
	 */
	@EntityGraph(attributePaths = { "catalogFile" })
	Page<Movement> findByStatusAndReasonAndMovedAtBeforeOrderByIdAsc(MovementStatus status, MovementReason reason,
			LocalDateTime cutoff, Pageable pageable);

	/**
	 * How many movement rows still reference a given media file - used to know when
	 * it is safe to delete it.
	 */
	long countByCatalogFileId(Long catalogFileId);

	/**
	 * Aggregated post-move integrity report: movement counts grouped by status and
	 * reason for one execution, ordered by count. A DB-side GROUP BY so it scales
	 * to the ~200k-movement runs without materializing every row.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.MovementSummaryResponse(
				CAST(m.status AS string),
				CAST(m.reason AS string),
				COUNT(m)
			)
			FROM Movement m
			WHERE m.execution.id = :executionId
			GROUP BY m.status, m.reason
			ORDER BY COUNT(m) DESC
			""")
	List<MovementSummaryResponse> summarizeByExecutionId(Long executionId);
}