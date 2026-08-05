package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.ExecutionTelemetryRow;

public interface ExecutionRepository extends JpaRepository<Execution, Long> {

	Optional<Execution> findByPublicId(UUID publicId);

	/**
	 * Flat performance-telemetry rows (executions that finished and were measured),
	 * optionally filtered by application version, most recent first.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.ExecutionTelemetryRow(
				e.id, e.publicId, e.executionType, e.status, e.startedAt, e.finishedAt,
				m.durationMillis, m.filesPerSecond, e.filesFound, e.errors, e.applicationVersion,
				m.workers, m.chunkSize, m.ffmpegPhotoHashLimit, m.ffprobeVideoLimit,
				m.photoHashJvmDecodable, m.photoHashFfmpegOnly, m.photoHashFailures)
			FROM Execution e JOIN ExecutionMetrics m ON m.id = e.id
			WHERE m.durationMillis IS NOT NULL
			  AND (:version IS NULL OR e.applicationVersion = :version)
			ORDER BY e.startedAt DESC
			""")
	List<ExecutionTelemetryRow> findTelemetry(@Param("version") String version, Pageable pageable);

	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.ExecutionTelemetryRow(
				e.id, e.publicId, e.executionType, e.status, e.startedAt, e.finishedAt,
				m.durationMillis, m.filesPerSecond, e.filesFound, e.errors, e.applicationVersion,
				m.workers, m.chunkSize, m.ffmpegPhotoHashLimit, m.ffprobeVideoLimit,
				m.photoHashJvmDecodable, m.photoHashFfmpegOnly, m.photoHashFailures)
			FROM Execution e LEFT JOIN ExecutionMetrics m ON m.id = e.id
			WHERE e.id = :id
			""")
	Optional<ExecutionTelemetryRow> findTelemetryById(@Param("id") Long id);

	/**
	 * Telemetry rows for a set of executions (comparison view), most recent first.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.ExecutionTelemetryRow(
				e.id, e.publicId, e.executionType, e.status, e.startedAt, e.finishedAt,
				m.durationMillis, m.filesPerSecond, e.filesFound, e.errors, e.applicationVersion,
				m.workers, m.chunkSize, m.ffmpegPhotoHashLimit, m.ffprobeVideoLimit,
				m.photoHashJvmDecodable, m.photoHashFfmpegOnly, m.photoHashFailures)
			FROM Execution e LEFT JOIN ExecutionMetrics m ON m.id = e.id
			WHERE e.id IN :ids
			ORDER BY e.startedAt DESC
			""")
	List<ExecutionTelemetryRow> findTelemetryByIds(@Param("ids") Collection<Long> ids);

	@Query("""
			SELECT DISTINCT e.applicationVersion
			FROM Execution e JOIN ExecutionMetrics m ON m.id = e.id
			WHERE m.durationMillis IS NOT NULL AND e.applicationVersion IS NOT NULL
			ORDER BY e.applicationVersion DESC
			""")
	List<String> findTelemetryVersions();

	List<Execution> findTop20ByOrderByStartedAtDesc();

	/**
	 * The functional history: what the user is shown, and what backs the
	 * Dashboard's infinite-scroll list - unlike findTop20ByOrderByStartedAtDesc,
	 * which is a fixed snapshot for the REST API, this lets the page keep loading
	 * further back as the user scrolls.
	 *
	 * <p>
	 * Everything except the automatic reconciles that changed nothing. Those run
	 * every few minutes and, on a library that is not moving, find nothing to do -
	 * hundreds of identical rows a day saying so would bury the runs that matter.
	 * The rows stay in the table: the queue and the technical audit are complete,
	 * and this is the one query that narrows them.
	 *
	 * <p>
	 * The exclusion is deliberately narrow. Only RECONCILE, only TIMER, only
	 * FINISHED, and only with nothing repaired - a manual reconcile, one queued by
	 * recovery, one that fixed anything, or any run that failed, was interrupted or
	 * was cancelled is always shown, because each of those is something someone
	 * might need to explain.
	 */
	@Query("""
			SELECT e FROM Execution e
			 WHERE e.executionType <> br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType.RECONCILE
			    OR e.triggerEvent IS NULL
			    OR e.triggerEvent <> br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger.TIMER
			    OR e.status <> br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus.FINISHED
			    OR e.repairedItems > 0
			 ORDER BY e.startedAt DESC
			""")
	Page<Execution> findFunctionalHistory(Pageable pageable);

	List<Execution> findByFinishedAtIsNullAndStatusIn(Collection<ExecutionStatus> statuses);

	/**
	 * The most recent run of one kind, whatever state it is in. The conversion
	 * screen asks it to answer both of its questions at once: whether a batch is
	 * going on, and - when none is - what the last one reported.
	 */
	Optional<Execution> findFirstByExecutionTypeOrderByCreatedAtDesc(ExecutionType executionType);

	/**
	 * The last reconciliation that actually completed - what the layout labels as
	 * such. Read rather than remembered, because the pass runs in the worker and
	 * the process rendering the page never sees it happen.
	 */
	Optional<Execution> findFirstByExecutionTypeAndStatusOrderByFinishedAtDesc(ExecutionType executionType,
			ExecutionStatus status);

	/**
	 * The request that made a duplicate a duplicate - the one already waiting or
	 * running for this type and target. Newest first, because a queue holds at
	 * most one pending and one running of a deduplicated type and the pending one
	 * is what a second asker is being told about.
	 */
	Optional<Execution> findFirstByExecutionTypeAndDedupKeyAndStatusInOrderByCreatedAtDesc(ExecutionType executionType,
			String dedupKey, Collection<ExecutionStatus> statuses);

	Optional<Execution> findFirstByFinishedAtIsNullAndStatusInOrderByStartedAtDesc(
			Collection<ExecutionStatus> statuses);

	/**
	 * Whether a run of one kind is going on right now, asked of every active row
	 * rather than of the most recent one. The difference matters since a worker
	 * runs more than one execution at a time: "the newest active execution is an
	 * inventory" stops being the same question as "is an inventory running", and
	 * answers it wrong whenever something else started later.
	 */
	boolean existsByExecutionTypeAndStatusIn(ExecutionType executionType, Collection<ExecutionStatus> statuses);

	/**
	 * The run of this type that is going on, if one is. Asked by the screens that
	 * describe a background job to the user: what it is doing has to be read from
	 * the row, because the process doing it is not this one.
	 */
	Optional<Execution> findFirstByExecutionTypeAndStatusInOrderByStartedAtDesc(ExecutionType executionType,
			Collection<ExecutionStatus> statuses);

	// --- Retention (cleanup of old executions) -----------------------------------
	// We only delete finished executions (finishedAt IS NOT NULL); executions in
	// progress are never touched. The bulk removal triggers the database FKs:
	// movement / execution_step / execution_phase / execution_metrics have ON
	// DELETE CASCADE and execution_error has ON DELETE SET NULL, so the children
	// are handled in SQL (execution_metrics is unidirectional, so the DB FK is its
	// only cleanup).

	/**
	 * IDs of the finished executions, most recent first - used by keepLatest to
	 * figure out which ones to preserve (via {@link Pageable}).
	 */
	@Query("SELECT e.id FROM Execution e WHERE e.finishedAt IS NOT NULL ORDER BY e.startedAt DESC")
	List<Long> findFinishedIdsByStartedAtDesc(Pageable pageable);

	@Modifying
	@Query("DELETE FROM Execution e WHERE e.finishedAt IS NOT NULL AND e.finishedAt < :cutoff")
	int deleteFinishedBefore(@Param("cutoff") LocalDateTime cutoff);

	@Modifying
	@Query("DELETE FROM Execution e WHERE e.finishedAt IS NOT NULL AND e.id NOT IN :keepIds")
	int deleteFinishedNotIn(@Param("keepIds") Collection<Long> keepIds);

	@Modifying
	@Query("DELETE FROM Execution e WHERE e.finishedAt IS NOT NULL")
	int deleteAllFinished();
}