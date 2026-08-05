package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingMetrics;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionPhaseTimings;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.PerformanceTelemetryService;

class InventoryTelemetryRecorderTest {

	private final ProcessingMetrics processingMetrics = new ProcessingMetrics();
	private final ExecutionPhaseTimings executionPhaseTimings = new ExecutionPhaseTimings();
	private final PerformanceTelemetryService performanceTelemetryService = mock(PerformanceTelemetryService.class);

	@Test
	void recordShouldPersistTheMetricsSnapshot() {
		InventoryTelemetryRecorder recorder = recorder();

		recorder.persist(5L, processingMetrics.snapshot());

		verify(performanceTelemetryService).recordMetrics(eq(5L), any(), any(), any());
	}

	@Test
	void recordShouldSwallowTelemetryFailuresWithoutPropagating() {
		InventoryTelemetryRecorder recorder = recorder();

		doThrow(new IllegalStateException("boom")).when(performanceTelemetryService).recordMetrics(any(), any(), any(),
				any());

		Assertions.assertThatNoException().isThrownBy(() -> recorder.persist(5L, processingMetrics.snapshot()));

		verify(performanceTelemetryService).recordMetrics(eq(5L), any(), any(), any());
	}

	private InventoryTelemetryRecorder recorder() {
		return new InventoryTelemetryRecorder(processingMetrics, executionPhaseTimings, performanceTelemetryService,
				new ProcessingProperties(null, null, null, null, null, 1));
	}
}