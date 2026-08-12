package br.com.jorgemelo.nimbusfilemanager.telemetry.application;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhaseType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExternalToolCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.CategorySnapshot;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.ConfigSnapshot;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.PhaseSnapshot;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.Snapshot;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.model.ExecutionMetrics;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.model.ExecutionMetricsCategory;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository.ExecutionMetricsCategoryRepository;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository.ExecutionMetricsRepository;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository.ExecutionPhaseRepository;

/**
 * Writes down what one attempt of one execution measured: the aggregate, the
 * macro phases and the cost of each external tool it used.
 *
 * <p>
 * <b>One transaction, fenced at the top.</b> The first thing it does is hold
 * the attempt in force in the database; everything after that happens under
 * that hold, and a taking that has been superseded gets no further than the
 * first statement. Checking in memory and then writing would leave a window
 * between the two - the recovery pass runs on a timer, so that window is not
 * theoretical - and a stale attempt reaching the writes could delete the phases
 * and categories of the taking that replaced it. Not just fail to add its own:
 * <em>delete</em> the current ones, because consolidation replaces.
 *
 * <p>
 * <b>Terminal is not stale.</b> The run is finished, cancelled or failed by the
 * time this is called - that is where the duration comes from - so the fence
 * deliberately says nothing about status or lease. Only a newer attempt
 * invalidates this one.
 */
@Service
public class PerformanceTelemetryService {

	private final ExecutionRepository executionRepository;
	private final ExecutionMetricsRepository executionMetricsRepository;
	private final ExecutionPhaseRepository executionPhaseRepository;
	private final ExecutionMetricsCategoryRepository executionMetricsCategoryRepository;
	private final String applicationVersion;
	private final Clock clock;

	public PerformanceTelemetryService(ExecutionRepository executionRepository,
			ExecutionMetricsRepository executionMetricsRepository, ExecutionPhaseRepository executionPhaseRepository,
			ExecutionMetricsCategoryRepository executionMetricsCategoryRepository,
			@Value("${application.version:unknown}") String applicationVersion, Clock clock) {
		this.executionRepository = executionRepository;
		this.executionMetricsRepository = executionMetricsRepository;
		this.executionPhaseRepository = executionPhaseRepository;
		this.executionMetricsCategoryRepository = executionMetricsCategoryRepository;
		this.applicationVersion = applicationVersion;
		this.clock = clock;
	}

	/**
	 * @return whether anything was written - false means a later attempt owns the
	 * row and this one's numbers were dropped, which is the fence working rather
	 * than an error
	 */
	@Transactional
	public boolean recordMetrics(ExecutionOwnership ownership, ExecutionMetricsContext context,
			ConfigSnapshot config) {
		if (!ownership.pinAttempt()) {
			return false;
		}

		Optional<Execution> found = executionRepository.findById(ownership.executionId());

		if (found.isEmpty()) {
			return false;
		}

		Execution execution = found.get();

		applyApplicationVersion(execution);

		executionRepository.save(execution);

		executionMetricsRepository.save(aggregate(execution, ownership.claimCount(), context.processing().snapshot(),
				config));

		replacePhases(ownership.executionId(), context.phases().snapshot());

		replaceCategories(ownership.executionId(), context.processing().snapshot());

		return true;
	}

	private void applyApplicationVersion(Execution execution) {
		if (execution.getApplicationVersion() == null || execution.getApplicationVersion().isBlank()) {
			execution.setApplicationVersion(applicationVersion);
		}
	}

	/**
	 * The row is per execution, not per attempt, so a reclaim overwrites what the
	 * previous taking left. {@code attemptClaimCount} records which one this is -
	 * the answer to "whose numbers are these?", and the reason a late writer
	 * cannot pretend to be the current one.
	 */
	private ExecutionMetrics aggregate(Execution execution, int claimCount, Snapshot metrics, ConfigSnapshot config) {
		ExecutionMetrics row = executionMetricsRepository.findById(execution.getId())
				.orElseGet(() -> newMetrics(execution));

		row.setAttemptClaimCount(claimCount);

		Long durationMillis = durationMillis(execution);

		row.setDurationMillis(durationMillis);
		row.setFilesPerSecond(durationMillis == null ? null : filesPerSecond(execution, durationMillis));

		row.setTasksExecuted(metrics.tasksExecuted());
		row.setTasksCacheAvoided(metrics.tasksCacheAvoided());
		row.setTasksCancelled(metrics.tasksCancelled());
		row.setTasksError(metrics.tasksError());

		row.setQueueWaitMillis(millis(metrics.queueWaitNanos()));
		row.setTaskTotalMillis(millis(metrics.taskTotalNanos()));
		row.setBatchWallClockMillis(millis(metrics.wallClockNanos()));

		row.setMaxConcurrency(metrics.maxConcurrency());

		if (config != null) {
			row.setWorkers(config.workers());
			row.setChunkSize(config.chunkSize());
			row.setFfmpegPhotoHashLimit(config.ffmpegPhotoHashLimit());
			row.setFfprobeVideoLimit(config.ffprobeVideoLimit());
		}

		return row;
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

	/**
	 * Replaces rather than appends: a reclaimed execution measures its phases
	 * again from zero, and adding them to what the previous attempt left would
	 * show a run that took twice as long as it did.
	 */
	private void replacePhases(Long executionId, Map<ExecutionPhaseType, PhaseSnapshot> phases) {
		executionPhaseRepository.deleteByExecutionId(executionId);

		if (phases.isEmpty()) {
			return;
		}

		List<ExecutionPhase> rows = new ArrayList<>(phases.size());

		phases.forEach((phase, snapshot) -> rows.add(ExecutionPhase.builder().executionId(executionId).phase(phase)
				.durationMillis(snapshot.durationMillis()).items(snapshot.items()).build()));

		executionPhaseRepository.saveAll(rows);
	}

	/**
	 * Only the categories this run actually used. A drain that never touched
	 * ffprobe has no ffprobe row, which is the difference between "did not use it"
	 * and "used it and it cost nothing".
	 */
	private void replaceCategories(Long executionId, Snapshot metrics) {
		executionMetricsCategoryRepository.deleteByExecutionId(executionId);

		List<ExecutionMetricsCategory> rows = new ArrayList<>();

		metrics.categories().forEach((category, snapshot) -> {
			if (used(snapshot)) {
				rows.add(categoryRow(executionId, category, snapshot));
			}
		});

		if (!rows.isEmpty()) {
			executionMetricsCategoryRepository.saveAll(rows);
		}
	}

	private boolean used(CategorySnapshot snapshot) {
		return snapshot.runs() > 0 || snapshot.gateWaitNanos() > 0 || snapshot.externalExecNanos() > 0;
	}

	private ExecutionMetricsCategory categoryRow(Long executionId, ExternalToolCategory category,
			CategorySnapshot snapshot) {
		return ExecutionMetricsCategory.builder().executionId(executionId).category(category).runs(snapshot.runs())
				.gateWaitMillis(millis(snapshot.gateWaitNanos()))
				.externalExecMillis(millis(snapshot.externalExecNanos())).build();
	}

	private long millis(long nanos) {
		return TimeUnit.NANOSECONDS.toMillis(nanos);
	}
}