package br.com.jorgemelo.nimbusfilemanager.shared.application;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The marker that says which role produced a line, and the two ways a marker
 * like this normally goes wrong.
 *
 * <p>
 * It leaks forward - a pooled thread keeps the role of the task that just
 * finished and lends it to the next one, which in a combined JVM is the other
 * role - or it never arrives, because the thread doing the work is not the
 * thread that was marked. Both are asserted here rather than reasoned about.
 */
class LoggingRoleTest {

	@AfterEach
	void leaveNothingBehind() {
		MDC.clear();
	}

	@Test
	void aThreadTheWorkerCreatedForItselfSaysSo() {
		LoggingRole.markThisThreadAsWorker();

		Assertions.assertThat(LoggingRole.current()).isEqualTo("WORKER");
	}

	/**
	 * Nothing set is the ordinary case, and it is not an error: the pattern falls
	 * back to the process's own role, which is right for every process that has
	 * only one.
	 */
	@Test
	void athreadNobodyMarkedCarriesNoRole() {
		Assertions.assertThat(LoggingRole.current()).isNull();
	}

	/** A role captured on one thread arrives on the thread that runs the work. */
	@Test
	void aRoleHandedToAnotherThreadArrivesThere() throws Exception {
		LoggingRole.markThisThreadAsWorker();

		String captured = LoggingRole.current();

		AtomicReference<String> seen = new AtomicReference<>();

		ExecutorService pool = Executors.newSingleThreadExecutor();

		try {
			pool.submit(() -> LoggingRole.runAs(captured, () -> seen.set(LoggingRole.current()))).get();
		} finally {
			pool.shutdownNow();
		}

		Assertions.assertThat(seen.get()).isEqualTo("WORKER");
	}

	/**
	 * The one that matters. The same pooled thread runs a worker task and then
	 * something nobody marked, and the second must not inherit the first's role -
	 * that is how a request would end up labelled as worker processing.
	 */
	@Test
	void aPooledThreadDoesNotCarryOneTasksRoleIntoTheNext() throws Exception {
		AtomicReference<String> afterwards = new AtomicReference<>("not run");

		ExecutorService pool = Executors.newSingleThreadExecutor();

		try {
			pool.submit(() -> LoggingRole.runAs("WORKER", () -> {
			})).get();

			pool.submit(() -> LoggingRole.runAs(null, () -> afterwards.set(LoggingRole.current()))).get();
		} finally {
			pool.shutdownNow();
		}

		Assertions.assertThat(afterwards.get()).isNull();
	}

	/** And a task that throws leaves the thread as clean as one that returns. */
	@Test
	void aTaskThatFailsStillGivesTheThreadBackUnmarked() throws Exception {
		AtomicReference<String> afterwards = new AtomicReference<>("not run");

		ExecutorService pool = Executors.newSingleThreadExecutor();

		try {
			pool.submit(() -> {
				try {
					LoggingRole.runAs("WORKER", () -> {
						throw new IllegalStateException("the work failed");
					});
				} catch (IllegalStateException _) {
					// The point is what the thread looks like afterwards, not the failure.
				}
			}).get();

			pool.submit(() -> afterwards.set(LoggingRole.current())).get();
		} finally {
			pool.shutdownNow();
		}

		Assertions.assertThat(afterwards.get()).isNull();
	}
}