package br.com.jorgemelo.nimbusfilemanager.worker.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * The difference between replacing a worker and spawning JVMs.
 *
 * <p>
 * Both cases end the same way - a process exited - and only its lifetime tells
 * them apart. A worker that ran and then died is replaced at once, which is the
 * behaviour that already existed; one that never got going is waited for,
 * longer each time, and eventually not replaced at all. Without the second
 * half, an incompatible schema means the application starts a JVM as fast as
 * one can fail, forever.
 */
class WorkerRestartPolicyTest {

	@Test
	void countsAShortLifeAsAFailureToStart() {
		assertThat(WorkerRestartPolicy.failedToStart(Duration.ofSeconds(1))).isTrue();
	}

	@Test
	void doesNotCountAWorkerThatRanForAWhile() {
		assertThat(WorkerRestartPolicy.failedToStart(Duration.ofMinutes(30))).isFalse();
	}

	@Test
	void addsUpFailuresThatKeepHappening() {
		assertThat(WorkerRestartPolicy.consecutiveFailuresAfter(2, Duration.ofSeconds(1))).isEqualTo(3);
	}

	/**
	 * The reset is what keeps a machine that has been running for months from
	 * ever reaching the give-up limit through failures spread over years.
	 */
	@Test
	void forgetsPastFailuresOnceAWorkerHasWorked() {
		assertThat(WorkerRestartPolicy.consecutiveFailuresAfter(5, Duration.ofHours(2))).isZero();
	}

	@Test
	void replacesAWorkingWorkerImmediately() {
		assertThat(WorkerRestartPolicy.delayAfter(0)).isEqualTo(Duration.ZERO);
	}

	@Test
	void waitsLongerAfterEachFailureToStart() {
		assertThat(WorkerRestartPolicy.delayAfter(1)).isEqualTo(Duration.ofSeconds(1));
		assertThat(WorkerRestartPolicy.delayAfter(2)).isEqualTo(Duration.ofSeconds(2));
		assertThat(WorkerRestartPolicy.delayAfter(3)).isEqualTo(Duration.ofSeconds(4));
	}

	/** Doubling has to stop somewhere, or the wait outlives whoever is waiting. */
	@Test
	void stopsDoublingAtAMinute() {
		assertThat(WorkerRestartPolicy.delayAfter(20)).isEqualTo(Duration.ofSeconds(60));
	}

	@Test
	void keepsTryingWhileTryingIsStillWorthIt() {
		assertThat(WorkerRestartPolicy.givesUpAfter(1)).isFalse();
		assertThat(WorkerRestartPolicy.givesUpAfter(7)).isFalse();
	}

	@Test
	void stopsReplacingAWorkerThatWillNotStart() {
		assertThat(WorkerRestartPolicy.givesUpAfter(8)).isTrue();
	}
}