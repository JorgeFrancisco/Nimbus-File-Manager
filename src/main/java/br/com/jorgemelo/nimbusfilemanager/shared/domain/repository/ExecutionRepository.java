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
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.ExecutionTelemetryRow;

public interface ExecutionRepository extends JpaRepository<Execution, Long> {

	Optional<Execution> findByExecutionPublicId(UUID executionPublicId);

	/**
	 * Flat performance-telemetry rows (executions that finished and were measured),
	 * optionally filtered by application version, most recent first.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.ExecutionTelemetryRow(
				e.id, e.executionPublicId, e.executionType, e.status, e.startedAt, e.finishedAt,
				m.durationMillis, m.filesPerSecond, e.filesFound, e.errors, e.applicationVersion,
				m.workers, m.chunkSize, m.ffmpegPhotoHashLimit, m.ffprobeVideoLimit)
			FROM Execution e JOIN ExecutionMetrics m ON m.id = e.id
			WHERE m.durationMillis IS NOT NULL
			  AND (:version IS NULL OR e.applicationVersion = :version)
			ORDER BY e.startedAt DESC
			""")
	List<ExecutionTelemetryRow> findTelemetry(@Param("version") String version, Pageable pageable);

	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.ExecutionTelemetryRow(
				e.id, e.executionPublicId, e.executionType, e.status, e.startedAt, e.finishedAt,
				m.durationMillis, m.filesPerSecond, e.filesFound, e.errors, e.applicationVersion,
				m.workers, m.chunkSize, m.ffmpegPhotoHashLimit, m.ffprobeVideoLimit)
			FROM Execution e LEFT JOIN ExecutionMetrics m ON m.id = e.id
			WHERE e.id = :id
			""")
	Optional<ExecutionTelemetryRow> findTelemetryById(@Param("id") Long id);

	/**
	 * Telemetry rows for a set of executions (comparison view), most recent first.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.ExecutionTelemetryRow(
				e.id, e.executionPublicId, e.executionType, e.status, e.startedAt, e.finishedAt,
				m.durationMillis, m.filesPerSecond, e.filesFound, e.errors, e.applicationVersion,
				m.workers, m.chunkSize, m.ffmpegPhotoHashLimit, m.ffprobeVideoLimit)
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

	/**
	 * Everything in flight, for the banner every page polls.
	 *
	 * <p>
	 * On status alone, which the index on that column serves, and never on the
	 * history: the set is small by construction - one waiting and one running per
	 * deduplication key, one at a time per type - while the history grows forever.
	 * Ordering is left to the caller, because the rule the banner follows is the
	 * queue's own and belongs with it rather than in a method name.
	 */
	List<Execution> findByStatusIn(Collection<ExecutionStatus> statuses);

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

	/**
	 * The run that is actually under way, which is not the same as the row that
	 * still says one is.
	 *
	 * <p>
	 * What owns a claimed row is its lease. A worker that dies stops renewing it
	 * but leaves the row RUNNING until something reclaims it, and at start-up that
	 * recovery happens after the watcher and the pages have already asked - so this
	 * asks what the queue itself asks, {@code lease_until < now}, and a claimed row
	 * counts only while its lease still holds. A row nobody has claimed has no
	 * lease and counts as it always did: work already asked for is work that will
	 * run.
	 *
	 * <p>
	 * Answering with the dead row was not cosmetic. It told the watcher something
	 * was running, so adopting the monitored folder queued a full pass instead of
	 * launching one; the pass could only fire once the queue went quiet, and by then
	 * an inventory had walked the same tree - so it walked 146k files to catalogue
	 * nothing, and took the fingerprint backlog down with it on the way in.
	 *
	 * @param claimed the status a row reaches by being claimed, which is the only
	 * one a lease speaks for
	 */
	@Query("""
			select e
			  from Execution e
			 where e.finishedAt is null
			   and e.status in :statuses
			   and (e.status <> :claimed or e.leaseUntil is null or e.leaseUntil >= :now)
			 order by e.startedAt desc
			""")
	List<Execution> findUnderWay(@Param("statuses") Collection<ExecutionStatus> statuses,
			@Param("claimed") ExecutionStatus claimed, @Param("now") LocalDateTime now, Pageable pageable);

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

	/**
	 * The retention passes below all carry the same guard, and it is not about
	 * history: a quarantined file is held on disk and the movement is the only
	 * record of where it came from, so an execution that still owns one is the
	 * difference between a restorable file and an orphan in a folder. Clearing
	 * old executions is a request to forget what happened, never to change what
	 * the library currently holds.
	 *
	 * <p>
	 * The reasons arrive as a parameter rather than being spelled here, so the
	 * set that protects an item is by construction the same one that lists and
	 * restores it - {@code QuarantineConstants.QUARANTINED_REASONS}. The moment a
	 * quarantine is restored or purged its movement row is deleted, and the
	 * execution becomes eligible again with no further bookkeeping.
	 */
	@Modifying
	@Query("""
			DELETE FROM Execution e
			 WHERE e.finishedAt IS NOT NULL
			   AND e.finishedAt < :cutoff
			   AND NOT EXISTS (SELECT 1 FROM Movement m
							  WHERE m.execution = e
								AND m.status = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus.MOVED
								AND m.reason IN :quarantineReasons)
			""")
	int deleteFinishedBefore(@Param("cutoff") LocalDateTime cutoff,
			@Param("quarantineReasons") Collection<MovementReason> quarantineReasons);

	@Modifying
	@Query("""
			DELETE FROM Execution e
			 WHERE e.finishedAt IS NOT NULL
			   AND e.id NOT IN :keepIds
			   AND NOT EXISTS (SELECT 1 FROM Movement m
							  WHERE m.execution = e
								AND m.status = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus.MOVED
								AND m.reason IN :quarantineReasons)
			""")
	int deleteFinishedNotIn(@Param("keepIds") Collection<Long> keepIds,
			@Param("quarantineReasons") Collection<MovementReason> quarantineReasons);

	@Modifying
	@Query("""
			DELETE FROM Execution e
			 WHERE e.finishedAt IS NOT NULL
			   AND NOT EXISTS (SELECT 1 FROM Movement m
							  WHERE m.execution = e
								AND m.status = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus.MOVED
								AND m.reason IN :quarantineReasons)
			""")
	int deleteAllFinished(@Param("quarantineReasons") Collection<MovementReason> quarantineReasons);
}