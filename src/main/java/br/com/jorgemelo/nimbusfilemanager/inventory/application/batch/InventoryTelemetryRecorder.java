package br.com.jorgemelo.nimbusfilemanager.inventory.application.batch;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingMetrics;
import br.com.jorgemelo.nimbusfilemanager.processing.application.dto.Snapshot;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhaseType;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionPhaseTimings;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.PerformanceTelemetryService;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.ConfigSnapshot;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.PhotoHashCounters;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the inventory job's performance instrumentation, extracted from
 * {@link InventoryJobExecutionListener} so the listener keeps a single
 * responsibility (driving the
 * {@link br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution}
 * status). Groups the processing metrics, per-phase timings and the telemetry
 * persistence that only make sense together.
 */
@Slf4j
@Component
public class InventoryTelemetryRecorder {

	private final ProcessingMetrics processingMetrics;
	private final ExecutionPhaseTimings executionPhaseTimings;
	private final PerformanceTelemetryService performanceTelemetryService;
	private final ProcessingProperties processingProperties;

	public InventoryTelemetryRecorder(ProcessingMetrics processingMetrics,
			ExecutionPhaseTimings executionPhaseTimings, PerformanceTelemetryService performanceTelemetryService,
			ProcessingProperties processingProperties) {
		this.processingMetrics = processingMetrics;
		this.executionPhaseTimings = executionPhaseTimings;
		this.performanceTelemetryService = performanceTelemetryService;
		this.processingProperties = processingProperties;
	}

	/**
	 * Isolate this inventory's processing metrics and phase timings from any
	 * previous run.
	 */
	public void reset() {
		processingMetrics.reset();

		executionPhaseTimings.reset();
	}

	void recordScanCount(long nanos, long items) {
		executionPhaseTimings.addNanos(ExecutionPhaseType.SCAN_COUNT, nanos);

		executionPhaseTimings.addItems(ExecutionPhaseType.SCAN_COUNT, items);
	}

	public Snapshot snapshot() {
		return processingMetrics.snapshot();
	}

	/**
	 * Persists the performance telemetry of this execution (duration, files/s,
	 * config snapshot, per-phase timings and the photo-hash format counters). Runs
	 * after the final status is set and the {@code finished_at} is committed, so
	 * the duration is available. Never lets a telemetry failure affect the job's
	 * outcome.
	 */
	public void persist(Long executionId, Snapshot metrics) {
		try {
			ConfigSnapshot config = new ConfigSnapshot(processingProperties.workersOrDefault(),
					InventoryJobConfig.CHUNK_SIZE, processingProperties.ffmpegPhotoHashLimitOrDefault(),
					processingProperties.ffprobeVideoLimitOrDefault());

			PhotoHashCounters counters = new PhotoHashCounters(metrics.photoHashJvmDecodable(),
					metrics.photoHashFfmpegOnly(), metrics.photoHashFailures());

			performanceTelemetryService.recordMetrics(executionId, config, executionPhaseTimings.snapshot(), counters);
		} catch (Exception exception) {
			log.warn("Could not record performance telemetry for execution {}", executionId, exception);
		}
	}
}