package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.constants.InventoryConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhaseType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionMetricsContext;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionTelemetryConsolidation;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.ConfigSnapshot;

class InventoryTelemetryRecorderTest {

	/** This test's own context: nothing here is shared with another run. */
	private final ExecutionMetricsContext context = new ExecutionMetricsContext();
	private final ExecutionTelemetryConsolidation telemetryConsolidation = mock(ExecutionTelemetryConsolidation.class);

	private final InventoryTelemetryRecorder recorder = new InventoryTelemetryRecorder(
			new ProcessingProperties(4, 200, 2, 3, 5, 1), telemetryConsolidation);

	@Test
	void recordsTheCountAgainstTheContextItWasGiven() {
		recorder.recordScanCount(context, 5_000_000L, 42L);

		Assertions.assertThat(context.phases().snapshot().get(ExecutionPhaseType.SCAN_COUNT).items()).isEqualTo(42);
	}

	/**
	 * Not any context: what the scan accumulated into is what has to reach the
	 * consolidation, or the row would describe a different run.
	 */
	@Test
	void handsTheConsolidationTheVeryContextTheScanMeasuredInto() {
		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		recorder.consolidate(ownership, context);

		verify(telemetryConsolidation).consolidate(same(ownership), eq(ExecutionType.INVENTORY), same(context), any());
	}

	/**
	 * The tuning in force travels with the numbers, because the same measurements
	 * mean different things at four workers and at twelve.
	 */
	@Test
	void carriesTheConfigurationTheRunWorkedUnder() {
		recorder.consolidate(mock(ExecutionOwnership.class), context);

		ArgumentCaptor<ConfigSnapshot> config = ArgumentCaptor.captor();

		verify(telemetryConsolidation).consolidate(any(), any(), any(), config.capture());

		Assertions.assertThat(config.getValue().workers()).isEqualTo(4);
		Assertions.assertThat(config.getValue().chunkSize()).isEqualTo(InventoryConstants.BATCH_SIZE);
		Assertions.assertThat(config.getValue().ffmpegPhotoHashLimit()).isEqualTo(2);
		Assertions.assertThat(config.getValue().ffprobeVideoLimit()).isEqualTo(5);
	}
}