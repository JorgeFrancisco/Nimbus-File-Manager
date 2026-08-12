package br.com.jorgemelo.nimbusfilemanager.telemetry.application;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.ConfigSnapshot;
import br.com.jorgemelo.nimbusfilemanager.telemetry.infrastructure.config.ExecutionMetricsProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * Where an execution's measurements stop being memory and become a row.
 *
 * <p>
 * Called once, at the end, by whoever owns the run - never per task, per file
 * or per batch. What a run costs is one write of one aggregate, one row per
 * macro phase and one row per external-tool category it actually used, and the
 * whole set is replaced together or not at all.
 *
 * <p>
 * <b>Two authorities, deliberately separate.</b> The
 * {@link ExecutionMetricsContext} says what was measured; the
 * {@link ExecutionOwnership} says whether this attempt may still write it down.
 * An attempt that was recovered and reclaimed by another taking holds a
 * perfectly valid context full of numbers nobody wants, and the only thing that
 * tells them apart is the claim count - which is why the ownership is a
 * parameter here rather than a field on the accumulator.
 *
 * <p>
 * <b>Failing is silent by design.</b> Telemetry that cannot be written must
 * never change how a run ended: the user's files are already where they belong
 * and the outcome is already committed. A refused fence is not even a failure -
 * it is the mechanism working.
 */
@Slf4j
@Service
public class ExecutionTelemetryConsolidation {

	private final PerformanceTelemetryService performanceTelemetryService;
	private final ExecutionMetricsProperties executionMetricsProperties;

	public ExecutionTelemetryConsolidation(PerformanceTelemetryService performanceTelemetryService,
			ExecutionMetricsProperties executionMetricsProperties) {
		this.performanceTelemetryService = performanceTelemetryService;
		this.executionMetricsProperties = executionMetricsProperties;
	}

	/**
	 * Writes down what this attempt measured, if its type is measured at all and
	 * if it is still the attempt the row belongs to.
	 *
	 * <p>
	 * Collection is always on; only persistence is configurable. A type that is
	 * switched off still accumulates - the cost is a handful of counters, and
	 * turning collection off too would mean the switch changed the behaviour of
	 * the run rather than what is kept about it.
	 */
	public void consolidate(ExecutionOwnership ownership, ExecutionType type, ExecutionMetricsContext context,
			ConfigSnapshot config) {
		if (!executionMetricsProperties.persists(type)) {
			return;
		}

		try {
			performanceTelemetryService.recordMetrics(ownership, context, config);
		} catch (RuntimeException exception) {
			log.warn("Could not record performance telemetry for execution {}", ownership.executionId(), exception);
		}
	}
}