package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection;

import java.time.LocalDateTime;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;

/**
 * Flat performance-telemetry view of one execution for the Statistics screen.
 * Only scalar columns, selected directly in JPQL, so no entity graph or lazy
 * collection is touched (open-in-view is off).
 */
public record ExecutionTelemetryRow(Long id, UUID publicId, ExecutionType executionType, ExecutionStatus status,
		LocalDateTime startedAt, LocalDateTime finishedAt, Long durationMillis, Double filesPerSecond,
		Integer filesFound, Integer errors, String applicationVersion, Integer workers, Integer chunkSize,
		Integer ffmpegPhotoHashLimit, Integer ffprobeVideoLimit) {
}