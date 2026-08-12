package br.com.jorgemelo.nimbusfilemanager.telemetry.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.EnumSet;
import java.util.Set;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.telemetry.infrastructure.config.ExecutionMetricsProperties;

/**
 * Which runs keep their measurements, and what happens when writing them down
 * fails.
 *
 * <p>
 * Collection is not configurable and persistence is: a type that is switched
 * off still accumulates, because turning the counters off would make the switch
 * change how the run behaves rather than what is kept about it.
 */
class ExecutionTelemetryConsolidationTest {

	private final PerformanceTelemetryService performanceTelemetryService = mock(PerformanceTelemetryService.class);

	private ExecutionTelemetryConsolidation consolidation(Set<ExecutionType> persisted) {
		ExecutionMetricsProperties properties = new ExecutionMetricsProperties();

		properties.setPersistedTypes(persisted);

		return new ExecutionTelemetryConsolidation(performanceTelemetryService, properties);
	}

	@Test
	void theDefaultKeepsTheInventoryAndBothFingerprintDrains() {
		ExecutionMetricsProperties properties = new ExecutionMetricsProperties();

		Assertions.assertThat(properties.getPersistedTypes()).containsExactlyInAnyOrder(ExecutionType.INVENTORY,
				ExecutionType.FINGERPRINT_PHOTO, ExecutionType.FINGERPRINT_VIDEO);
	}

	/** A: nothing configured, nothing written. */
	@Test
	void anEmptySetPersistsNothing() {
		consolidation(EnumSet.noneOf(ExecutionType.class)).consolidate(mock(ExecutionOwnership.class),
				ExecutionType.INVENTORY, new ExecutionMetricsContext(), null);

		verify(performanceTelemetryService, never()).recordMetrics(any(), any(), any());
	}

	/** B: only what was named, and the naming is by type rather than by feature. */
	@Test
	void onlyTheNamedTypeIsPersisted() {
		ExecutionTelemetryConsolidation consolidation = consolidation(EnumSet.of(ExecutionType.INVENTORY));

		consolidation.consolidate(mock(ExecutionOwnership.class), ExecutionType.INVENTORY,
				new ExecutionMetricsContext(), null);
		consolidation.consolidate(mock(ExecutionOwnership.class), ExecutionType.FINGERPRINT_PHOTO,
				new ExecutionMetricsContext(), null);

		verify(performanceTelemetryService).recordMetrics(any(), any(), any());
	}

	/** C and D: each drain can be switched on by itself. */
	@Test
	void eachFingerprintDrainCanBePersistedOnItsOwn() {
		consolidation(EnumSet.of(ExecutionType.FINGERPRINT_PHOTO)).consolidate(mock(ExecutionOwnership.class),
				ExecutionType.FINGERPRINT_PHOTO, new ExecutionMetricsContext(), null);

		consolidation(EnumSet.of(ExecutionType.FINGERPRINT_VIDEO)).consolidate(mock(ExecutionOwnership.class),
				ExecutionType.FINGERPRINT_VIDEO, new ExecutionMetricsContext(), null);

		verify(performanceTelemetryService, times(2)).recordMetrics(any(), any(), any());
	}

	/**
	 * E: a type that is switched off still measures. The context it accumulated
	 * into is a real one with real numbers - only the write is skipped.
	 */
	@Test
	void aTypeThatIsNotPersistedStillMeasuresInMemory() {
		ExecutionMetricsContext context = new ExecutionMetricsContext();

		context.processing().incExecuted();

		consolidation(EnumSet.of(ExecutionType.INVENTORY)).consolidate(mock(ExecutionOwnership.class),
				ExecutionType.CONVERSION, context, null);

		Assertions.assertThat(context.processing().snapshot().tasksExecuted()).isEqualTo(1);

		verify(performanceTelemetryService, never()).recordMetrics(any(), any(), any());
	}

	/**
	 * Telemetry that cannot be written must never change how a run ended: the
	 * user's files are already where they belong and the outcome is committed.
	 */
	@Test
	void aFailureToWriteTelemetryNeverReachesTheCaller() {
		doThrow(new IllegalStateException("boom")).when(performanceTelemetryService).recordMetrics(any(), any(),
				any());

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		when(ownership.executionId()).thenReturn(5L);

		Assertions.assertThatNoException()
				.isThrownBy(() -> consolidation(EnumSet.of(ExecutionType.INVENTORY)).consolidate(ownership,
						ExecutionType.INVENTORY, new ExecutionMetricsContext(), null));
	}

	/** A null set is the same as none, not a crash at the first consolidation. */
	@Test
	void clearingTheConfiguredTypesPersistsNothing() {
		ExecutionMetricsProperties properties = new ExecutionMetricsProperties();

		properties.setPersistedTypes(null);

		Assertions.assertThat(properties.persists(ExecutionType.INVENTORY)).isFalse();
	}
}