package br.com.jorgemelo.nimbusfilemanager.telemetry.application;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhaseType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.ConfigSnapshot;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.PhaseSnapshot;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.PhotoHashCounters;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.model.ExecutionMetrics;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository.ExecutionMetricsRepository;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository.ExecutionPhaseRepository;

/**
 * Persists the performance telemetry of a finished execution: one
 * {@code execution_metrics} row (duration, files/s, config snapshot and
 * photo-hash counters) plus one {@code execution_phase} row per measured phase.
 * The application version stays on the {@code execution} row. Called once per
 * execution, so it never affects the run's throughput.
 */
@Service
public class PerformanceTelemetryService {

	private final ExecutionRepository executionRepository;
	private final ExecutionMetricsRepository executionMetricsRepository;
	private final ExecutionPhaseRepository executionPhaseRepository;
	private final String applicationVersion;
	private final Clock clock;

	public PerformanceTelemetryService(ExecutionRepository executionRepository,
			ExecutionMetricsRepository executionMetricsRepository, ExecutionPhaseRepository executionPhaseRepository,
			@Value("${application.version:unknown}") String applicationVersion, Clock clock) {
		this.executionRepository = executionRepository;
		this.executionMetricsRepository = executionMetricsRepository;
		this.executionPhaseRepository = executionPhaseRepository;
		this.applicationVersion = applicationVersion;
		this.clock = clock;
	}

	@Transactional
	public void recordMetrics(Long executionId, ConfigSnapshot config, Map<ExecutionPhaseType, PhaseSnapshot> phases) {
		doRecord(executionId, config, phases, null);
	}

	@Transactional
	public void recordMetrics(Long executionId, ConfigSnapshot config, Map<ExecutionPhaseType, PhaseSnapshot> phases,
			PhotoHashCounters counters) {
		doRecord(executionId, config, phases, counters);
	}

	private void doRecord(Long executionId, ConfigSnapshot config, Map<ExecutionPhaseType, PhaseSnapshot> phases,
			PhotoHashCounters counters) {
		if (executionId == null) {
			return;
		}

		Optional<Execution> found = executionRepository.findById(executionId);

		if (found.isEmpty()) {
			return;
		}

		Execution execution = found.get();

		applyApplicationVersion(execution);

		executionRepository.save(execution);

		ExecutionMetrics metrics = buildMetrics(execution, config, counters);

		if (metrics != null) {
			executionMetricsRepository.save(metrics);
		}

		persistPhases(executionId, phases);
	}

	private void applyApplicationVersion(Execution execution) {
		if (execution.getApplicationVersion() == null || execution.getApplicationVersion().isBlank()) {
			execution.setApplicationVersion(applicationVersion);
		}
	}

	/**
	 * Finds or creates the {@code execution_metrics} row and applies whatever is
	 * available (duration, config, counters). Returns {@code null} when there is
	 * nothing to store, so an empty row is never created. {@link ExecutionMetrics}
	 * is unidirectional now, so it is looked up and saved through its own
	 * repository instead of cascading from {@code Execution}.
	 */
	private ExecutionMetrics buildMetrics(Execution execution, ConfigSnapshot config, PhotoHashCounters counters) {
		Long durationMillis = durationMillis(execution);

		if (durationMillis == null && config == null && counters == null) {
			return null;
		}

		ExecutionMetrics metrics = executionMetricsRepository.findById(execution.getId())
				.orElseGet(() -> newMetrics(execution));

		if (durationMillis != null) {
			metrics.setDurationMillis(durationMillis);
			metrics.setFilesPerSecond(filesPerSecond(execution, durationMillis));
		}

		if (config != null) {
			metrics.setWorkers(config.workers());
			metrics.setChunkSize(config.chunkSize());
			metrics.setFfmpegPhotoHashLimit(config.ffmpegPhotoHashLimit());
			metrics.setFfprobeVideoLimit(config.ffprobeVideoLimit());
		}

		if (counters != null) {
			metrics.setPhotoHashJvmDecodable(counters.jvmDecodable());
			metrics.setPhotoHashFfmpegOnly(counters.ffmpegOnly());
			metrics.setPhotoHashFailures(counters.failures());
		}

		return metrics;
	}

	private ExecutionMetrics newMetrics(Execution execution) {
		ExecutionMetrics metrics = new ExecutionMetrics();

		metrics.setExecution(execution);

		return metrics;
	}

	/**
	 * True elapsed time of the run in millis, or {@code null} when either bound is
	 * missing.
	 *
	 * <p>
	 * Pragmatic, localized fix for Sonar S8700: startedAt/finishedAt are stored as
	 * zone-less LocalDateTime, so convert both to an Instant using the application
	 * clock's zone before measuring the duration. This yields the true elapsed time
	 * across a DST spring-forward instead of the wall-clock delta. It does not
	 * remove the ambiguity inherent to LocalDateTime storage: on a DST fall-back
	 * overlap the original offset is already lost, so atZone() reconstructs only
	 * one of the two possible instants. Future work: adopt Instant for technical
	 * timestamps and a monotonic time source for duration metrics.
	 */
	private Long durationMillis(Execution execution) {
		if (execution.getStartedAt() == null || execution.getFinishedAt() == null) {
			return null;
		}

		ZoneId zone = clock.getZone();

		return Duration.between(execution.getStartedAt().atZone(zone).toInstant(),
				execution.getFinishedAt().atZone(zone).toInstant()).toMillis();
	}

	private double filesPerSecond(Execution execution, long durationMillis) {
		// Inventory populates filesFound; Organization populates filesMoved. Use the
		// larger so files/s is meaningful for both operation types.
		long filesFound = execution.getFilesFound() == null ? 0 : execution.getFilesFound();
		long filesMoved = execution.getFilesMoved() == null ? 0 : execution.getFilesMoved();
		long files = Math.max(filesFound, filesMoved);

		return durationMillis > 0 ? files * 1000.0 / durationMillis : 0.0;
	}

	private void persistPhases(Long executionId, Map<ExecutionPhaseType, PhaseSnapshot> phases) {
		if (phases == null || phases.isEmpty()) {
			return;
		}

		List<ExecutionPhase> rows = new ArrayList<>(phases.size());

		phases.forEach((phase, snapshot) -> rows.add(ExecutionPhase.builder().executionId(executionId).phase(phase)
				.durationMillis(snapshot.durationMillis()).items(snapshot.items()).build()));

		executionPhaseRepository.saveAll(rows);
	}
}