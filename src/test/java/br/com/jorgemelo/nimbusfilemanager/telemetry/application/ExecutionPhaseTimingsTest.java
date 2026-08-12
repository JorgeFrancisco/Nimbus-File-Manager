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

	/**
	 * What replaced the clearing: a run gets timings of its own, so what one
	 * measured is not visible to the next.
	 */
	@Test
	void aFreshSetOfTimingsStartsEmptyAndCannotSeeAnother() {
		ExecutionPhaseTimings first = new ExecutionPhaseTimings();

		first.addNanos(ExecutionPhaseType.PERSISTENCE, TimeUnit.MILLISECONDS.toNanos(30));
		first.addItems(ExecutionPhaseType.EXTRACTION, 7);

		assertThat(new ExecutionPhaseTimings().snapshot()).isEmpty();
		assertThat(first.snapshot()).as("the other one kept everything it measured").isNotEmpty();
	}
}