package br.com.jorgemelo.nimbusfilemanager.telemetry.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhaseType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.ExecutionTelemetryRow;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PageUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.TextUtils;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.ExecutionComparison;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.PhaseComparisonRow;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.model.ExecutionMetrics;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.model.ExecutionMetricsCategory;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository.ExecutionMetricsCategoryRepository;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository.ExecutionMetricsRepository;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository.ExecutionPhaseRepository;

/**
 * Read side of the performance telemetry: lists measured executions (optionally
 * by version) and loads the per-phase breakdown of one execution, for the
 * Statistics screen.
 */
@Service
@Transactional(readOnly = true)
public class ExecutionTelemetryQueryService {

	private static final int RECENT_LIMIT = 50;

	private final ExecutionRepository executionRepository;
	private final ExecutionPhaseRepository executionPhaseRepository;
	private final ExecutionMetricsRepository executionMetricsRepository;
	private final ExecutionMetricsCategoryRepository executionMetricsCategoryRepository;

	public ExecutionTelemetryQueryService(ExecutionRepository executionRepository,
			ExecutionPhaseRepository executionPhaseRepository,
			ExecutionMetricsRepository executionMetricsRepository,
			ExecutionMetricsCategoryRepository executionMetricsCategoryRepository) {
		this.executionRepository = executionRepository;
		this.executionPhaseRepository = executionPhaseRepository;
		this.executionMetricsRepository = executionMetricsRepository;
		this.executionMetricsCategoryRepository = executionMetricsCategoryRepository;
	}

	public List<ExecutionTelemetryRow> recent(String version) {
		return executionRepository.findTelemetry(TextUtils.blankToNull(version), PageUtils.firstPage(RECENT_LIMIT));
	}

	public Optional<ExecutionTelemetryRow> byId(Long id) {
		return executionRepository.findTelemetryById(id);
	}

	public List<ExecutionPhase> phases(Long executionId) {
		return executionPhaseRepository.findByExecutionIdOrderByPhaseAsc(executionId);
	}

	/**
	 * The aggregate of the attempt that measured the run: counts, waits and the
	 * configuration it worked under. Absent for an execution nobody measured,
	 * which the screen has to survive rather than treat as an error.
	 */
	public Optional<ExecutionMetrics> aggregate(Long executionId) {
		return executionMetricsRepository.findById(executionId);
	}

	/**
	 * What each external tool cost this run. Only the categories it actually
	 * used have a row, so an empty list means the run spawned no external
	 * process - not that it spawned some for free.
	 */
	public List<ExecutionMetricsCategory> categories(Long executionId) {
		return executionMetricsCategoryRepository.findByExecutionIdOrderByCategoryAsc(executionId);
	}

	public List<String> versions() {
		return executionRepository.findTelemetryVersions();
	}

	/**
	 * Builds the side-by-side comparison of the given executions: the telemetry
	 * rows (deduplicated, most recent first) and, for every phase that occurred in
	 * any of them, the per-execution durations aligned to that same order.
	 */
	public ExecutionComparison compare(Collection<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return new ExecutionComparison(List.of(), List.of(), 0);
		}

		List<Long> distinctIds = ids.stream().filter(Objects::nonNull).distinct().toList();

		// findTelemetryByIds already returns them most-recent-first; that fixed order
		// is
		// the column order every phase row aligns to.
		List<ExecutionTelemetryRow> executions = executionRepository.findTelemetryByIds(distinctIds);

		if (executions.isEmpty()) {
			return new ExecutionComparison(List.of(), List.of(), 0);
		}

		List<Long> orderedIds = executions.stream().map(ExecutionTelemetryRow::id).toList();

		Map<Long, Map<ExecutionPhaseType, Long>> byExecution = phasesByExecution(orderedIds);

		List<PhaseComparisonRow> rows = new ArrayList<>();

		long maxPhaseMillis = 0;

		for (ExecutionPhaseType phase : ExecutionPhaseType.values()) {
			boolean present = byExecution.values().stream().anyMatch(map -> map.containsKey(phase));

			if (!present) {
				continue;
			}

			List<Long> durations = new ArrayList<>(orderedIds.size());

			for (Long executionId : orderedIds) {
				long millis = byExecution.getOrDefault(executionId, Map.of()).getOrDefault(phase, 0L);

				durations.add(millis);
				maxPhaseMillis = Math.max(maxPhaseMillis, millis);
			}

			rows.add(new PhaseComparisonRow(phase, durations));
		}

		return new ExecutionComparison(executions, rows, maxPhaseMillis);
	}

	private Map<Long, Map<ExecutionPhaseType, Long>> phasesByExecution(List<Long> executionIds) {
		Map<Long, Map<ExecutionPhaseType, Long>> byExecution = new HashMap<>();

		for (ExecutionPhase phase : executionPhaseRepository
				.findByExecutionIdInOrderByExecutionIdAscPhaseAsc(executionIds)) {
			byExecution.computeIfAbsent(phase.getExecutionId(), _ -> new EnumMap<>(ExecutionPhaseType.class))
					.merge(phase.getPhase(), phase.getDurationMillis(), Long::sum);
		}

		return byExecution;
	}
}