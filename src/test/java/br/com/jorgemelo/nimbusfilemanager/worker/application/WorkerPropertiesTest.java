package br.com.jorgemelo.nimbusfilemanager.worker.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Every setting has to work unset: the product ships without a worker section
 * in its properties, and a null there must mean the documented default rather
 * than a zero that would stop the worker from ever claiming anything.
 */
class WorkerPropertiesTest {

	@Test
	void fallsBackToTheDocumentedDefaults() {
		WorkerProperties properties = new WorkerProperties(null, null, null, null, null, null, null, null, null, null);

		assertThat(properties.maxConcurrentOrDefault()).isEqualTo(WorkerProperties.DEFAULT_MAX_CONCURRENT);
		assertThat(properties.leaseSecondsOrDefault()).isEqualTo(WorkerProperties.DEFAULT_LEASE_SECONDS);
		assertThat(properties.renewSecondsOrDefault()).isEqualTo(WorkerProperties.DEFAULT_RENEW_SECONDS);
		assertThat(properties.pollSecondsOrDefault()).isEqualTo(WorkerProperties.DEFAULT_POLL_SECONDS);
		assertThat(properties.maxClaimsOrDefault()).isEqualTo(WorkerProperties.DEFAULT_MAX_CLAIMS);
		assertThat(properties.lockBackoffSecondsOrDefault()).isEqualTo(WorkerProperties.DEFAULT_LOCK_BACKOFF_SECONDS);
		assertThat(properties.initialHeapOrDefault()).isEqualTo(WorkerProperties.DEFAULT_INITIAL_HEAP);
		assertThat(properties.maxHeapOrDefault()).isEqualTo(WorkerProperties.DEFAULT_MAX_HEAP);
	}

	/**
	 * A blank value in a properties file is a value someone meant to remove, not a
	 * heap of zero size.
	 */
	@Test
	void treatsABlankHeapAsUnset() {
		WorkerProperties properties = new WorkerProperties(null, null, null, null, null, null, null, "  ", "", null);

		assertThat(properties.initialHeapOrDefault()).isEqualTo(WorkerProperties.DEFAULT_INITIAL_HEAP);
		assertThat(properties.maxHeapOrDefault()).isEqualTo(WorkerProperties.DEFAULT_MAX_HEAP);
	}

	@Test
	void keepsWhatWasConfigured() {
		WorkerProperties properties = new WorkerProperties(8, 300, 60, 2, 5, 30, 45, "1g", "2g", null);

		assertThat(properties.maxConcurrentOrDefault()).isEqualTo(8);
		assertThat(properties.leaseSecondsOrDefault()).isEqualTo(300);
		assertThat(properties.renewSecondsOrDefault()).isEqualTo(60);
		assertThat(properties.pollSecondsOrDefault()).isEqualTo(2);
		assertThat(properties.maxClaimsOrDefault()).isEqualTo(5);
		assertThat(properties.lockBackoffSecondsOrDefault()).isEqualTo(30);
		assertThat(properties.initialHeapOrDefault()).isEqualTo("1g");
		assertThat(properties.maxHeapOrDefault()).isEqualTo("2g");
	}

	/**
	 * The lease has to outlast several renewal rounds. If it did not, one slow
	 * round would hand a running execution to another claimer.
	 */
	@Test
	void leavesRoomForSeveralRenewalRoundsInsideOneLease() {
		WorkerProperties properties = new WorkerProperties(null, null, null, null, null, null, null, null, null, null);

		assertThat(properties.leaseSecondsOrDefault()).isGreaterThan(3 * properties.renewSecondsOrDefault());
	}
}