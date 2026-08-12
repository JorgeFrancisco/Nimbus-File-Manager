package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository;

import java.time.Instant;
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

import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.projection.MovementSummaryResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;

public interface MovementRepository extends JpaRepository<Movement, Long> {

	@EntityGraph(attributePaths = { "execution", "catalogFile" })
	List<Movement> findByExecutionIdAndStatusInOrderByIdDesc(Long executionId, Collection<MovementStatus> statuses);

	@EntityGraph(attributePaths = { "execution", "catalogFile" })
	List<Movement> findByExecutionIdOrderByIdAsc(Long executionId);

	/**
	 * Files still sitting in quarantine (soft-deleted and not restored yet): status
	 * {@code MOVED} and any quarantine reason. The reason is a collection because
	 * more than one feature soft-deletes into the same quarantine (duplicate
	 * removal and the original of a video conversion), and the Quarentena screen
	 * they all feed lists them together.
	 */
	@EntityGraph(attributePaths = { "execution", "catalogFile" })
	Page<Movement> findByStatusAndReasonInOrderByIdDesc(MovementStatus status, Collection<MovementReason> reasons,
			Pageable pageable);

	@EntityGraph(attributePaths = { "execution", "catalogFile" })
	Optional<Movement> findByMovementPublicId(UUID movementPublicId);

	/**
	 * Still-quarantined files whose soft-delete happened before {@code cutoff}: the
	 * retention purge uses this to find what is old enough to expunge. Oldest first
	 * so a capped run always tackles the most overdue items.
	 */
	@EntityGraph(attributePaths = { "catalogFile" })
	Page<Movement> findByStatusAndReasonInAndMovedAtBeforeOrderByIdAsc(MovementStatus status,
			Collection<MovementReason> reasons, Instant cutoff, Pageable pageable);

	/**
	 * Whether anything at all is overdue, without loading it. The daily pass asks
	 * this before queueing itself, so a day with nothing to expunge leaves no row
	 * behind on the executions screen.
	 */
	boolean existsByStatusAndReasonInAndMovedAtBefore(MovementStatus status, Collection<MovementReason> reasons,
			LocalDateTime cutoff);

	/**
	 * How many quarantine operations still hold a given file, which is the only
	 * question that decides whether the catalog row may go.
	 *
	 * <p>
	 * Counting <em>every</em> movement was the same question asked wrongly, and it
	 * only looked right while a quarantined file had exactly one movement to its
	 * name. Once a folder relocation writes one operation per file, an ordinary
	 * rename would hold a purge back forever - and silently, since a purge that
	 * removes nothing reports success.
	 */
	long countByCatalogFileIdAndStatusAndReasonIn(Long catalogFileId, MovementStatus status,
			Collection<MovementReason> reasons);

	/**
	 * Aggregated post-move integrity report: movement counts grouped by status and
	 * reason for one execution, ordered by count. A DB-side GROUP BY so it scales
	 * to the ~200k-movement runs without materializing every row.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.projection.MovementSummaryResponse(
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