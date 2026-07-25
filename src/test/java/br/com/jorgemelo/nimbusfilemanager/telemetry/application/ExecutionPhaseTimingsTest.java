package br.com.jorgemelo.nimbusfilemanager.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhaseType;

class ExecutionPhaseTimingsTest {

	@Test
	void snapshotIncludesOnlyTouchedPhasesInMillis() {
		ExecutionPhaseTimings timings = new ExecutionPhaseTimings();

		timings.addNanos(ExecutionPhaseType.CACHE_CHECK, TimeUnit.MILLISECONDS.toNanos(50));
		timings.addNanos(ExecutionPhaseType.EXTRACTION, TimeUnit.MILLISECONDS.toNanos(200));
		timings.addItems(ExecutionPhaseType.EXTRACTION, 10);

		var snapshot = timings.snapshot();

		assertThat(snapshot).containsOnlyKeys(ExecutionPhaseType.CACHE_CHECK, ExecutionPhaseType.EXTRACTION);
		assertThat(snapshot.get(ExecutionPhaseType.CACHE_CHECK).durationMillis()).isEqualTo(50);
		assertThat(snapshot.get(ExecutionPhaseType.CACHE_CHECK).items()).isZero();
		assertThat(snapshot.get(ExecutionPhaseType.EXTRACTION).durationMillis()).isEqualTo(200);
		assertThat(snapshot.get(ExecutionPhaseType.EXTRACTION).items()).isEqualTo(10);
	}

	@Test
	void resetClearsAllPhases() {
		ExecutionPhaseTimings timings = new ExecutionPhaseTimings();

		timings.addNanos(ExecutionPhaseType.PERSISTENCE, TimeUnit.MILLISECONDS.toNanos(30));
		// Populate the item counters too, so reset() must clear both maps (durations
		// and items), not just the durations.
		timings.addItems(ExecutionPhaseType.EXTRACTION, 7);

		timings.reset();

		assertThat(timings.snapshot()).isEmpty();
	}
}