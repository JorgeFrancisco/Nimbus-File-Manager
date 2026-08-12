package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.constants.InventoryConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhaseType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionMetricsContext;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionTelemetryConsolidation;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.ConfigSnapshot;

/**
 * Owns the inventory scan's performance instrumentation, so whoever drives the
 * {@link br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution}
 * status keeps a single responsibility.
 *
 * <p>
 * It holds no accumulator of its own any more. It used to be handed the two
 * Spring-managed ones, which is what let a scan clear numbers another execution
 * was still adding to; now the scan brings its own context and this only reads
 * from whichever one it is given.
 */
@Component
public class InventoryTelemetryRecorder {

	private final ProcessingProperties processingProperties;
	private final ExecutionTelemetryConsolidation telemetryConsolidation;

	public InventoryTelemetryRecorder(ProcessingProperties processingProperties,
			ExecutionTelemetryConsolidation telemetryConsolidation) {
		this.processingProperties = processingProperties;
		this.telemetryConsolidation = telemetryConsolidation;
	}

	/**
	 * Isolation is no longer something this has to arrange. The accumulators used
	 * to be shared and were cleared before each run, which only worked while one
	 * execution existed at a time; the scan now brings the context it accumulated
	 * into, and two of them cannot see each other.
	 */
	void recordScanCount(ExecutionMetricsContext context, long nanos, long items) {
		context.phases().addNanos(ExecutionPhaseType.SCAN_COUNT, nanos);

		context.phases().addItems(ExecutionPhaseType.SCAN_COUNT, items);
	}

	/**
	 * Writes down what the scan measured, after its outcome is committed.
	 *
	 * <p>
	 * The configuration goes with the numbers because the same measurements mean
	 * different things at four workers and at twelve.
	 */
	void consolidate(ExecutionOwnership ownership, ExecutionMetricsContext context) {
		ConfigSnapshot config = new ConfigSnapshot(processingProperties.workersOrDefault(),
				InventoryConstants.BATCH_SIZE, processingProperties.ffmpegPhotoHashLimitOrDefault(),
				processingProperties.ffprobeVideoLimitOrDefault());

		telemetryConsolidation.consolidate(ownership, ExecutionType.INVENTORY, context, config);
	}
}