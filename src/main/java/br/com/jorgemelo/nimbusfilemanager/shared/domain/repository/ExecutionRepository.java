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
	 * Backs the Dashboard's infinite-scroll execution list (DashboardWebController)
	 * - unlike findTop20ByOrderByStartedAtDesc, which is a fixed snapshot for the
	 * REST API, this lets the page keep loading further back in history as the user
	 * scrolls.
	 */
	Page<Execution> findAllByOrderByStartedAtDesc(Pageable pageable);

	List<Execution> findByFinishedAtIsNullAndStatusIn(Collection<ExecutionStatus> statuses);

	Optional<Execution> findFirstByFinishedAtIsNullAndStatusInOrderByStartedAtDesc(
			Collection<ExecutionStatus> statuses);

	// --- Retention (cleanup of old executions) -----------------------------------
	// We only delete finished executions (finishedAt IS NOT NULL); executions in
	// progress are never touched. The bulk removal triggers the database FKs:
	// movement / execution_step / execution_phase / execution_metrics have ON
	// DELETE CASCADE and execution_error has ON DELETE SET NULL, so the children are
	// handled in SQL (execution_metrics is unidirectional, so the DB FK is its only
	// cleanup).

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