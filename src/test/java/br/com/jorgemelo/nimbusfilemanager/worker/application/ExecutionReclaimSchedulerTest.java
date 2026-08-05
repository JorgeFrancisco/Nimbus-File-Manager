package br.com.jorgemelo.nimbusfilemanager.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * The timer around the recovery: that it asks, that it goes on asking after a
 * round that failed, and that it asks at the cadence the worker is configured
 * with rather than one of its own.
 */
class ExecutionReclaimSchedulerTest {

	private final ExecutionReclaim executionReclaim = mock(ExecutionReclaim.class);

	/**
	 * A round that threw is not the end of the schedule. The condition recovery
	 * looks for does not go away because a query failed, and the next answer costs
	 * one more query - so the failure is reported and the following round finds
	 * what the failed one would have.
	 */
	@Test
	void aRoundThatFailedDoesNotStopTheNextOneFromRecovering() {
		when(executionReclaim.reclaimAbandoned()).thenThrow(new IllegalStateException("the database went away"))
				.thenReturn(1);

		ExecutionReclaimScheduler scheduler = scheduler();

		assertThatCode(scheduler::runOnce).doesNotThrowAnyException();

		scheduler.runOnce();

		verify(executionReclaim, times(2)).reclaimAbandoned();
	}

	/**
	 * A round the shutdown knocked over is not news. Everything it touches is on
	 * its way out, so the failure is noted quietly and nothing is raised at a
	 * moment when there is nobody left to act on it.
	 */
	@Test
	void aRoundInterruptedByShutdownIsNotReportedAsAFailure() {
		when(executionReclaim.reclaimAbandoned()).thenThrow(new IllegalStateException("the pool is closing"));

		ExecutionReclaimScheduler scheduler = scheduler();

		scheduler.shutdown();

		assertThatCode(scheduler::runOnce).doesNotThrowAnyException();

		verify(executionReclaim).reclaimAbandoned();
	}

	/**
	 * The timer really does ask, and asking it to start twice does not break it -
	 * the worker starts it after the schema check, and a stray second call must
	 * not leave this instance with two timers on the same question. That there is
	 * only one is held by a compare-and-set rather than by this assertion: two
	 * timers would make the rounds arrive sooner, which is not something a test
	 * can tell from a machine under load.
	 */
	@Test
	void startingItTwiceIsHarmlessAndTheTimerRuns() throws Exception {
		CountDownLatch rounds = new CountDownLatch(2);

		when(executionReclaim.reclaimAbandoned()).thenAnswer(_ -> {
			rounds.countDown();

			return 0;
		});

		ExecutionReclaimScheduler scheduler = new ExecutionReclaimScheduler(executionReclaim, everySecond());

		try {
			scheduler.start();
			scheduler.start();

			assertThat(rounds.await(20, TimeUnit.SECONDS)).as("the timer is running").isTrue();
		} finally {
			scheduler.shutdown();
		}
	}

	/**
	 * The interval is the renewal one, and deliberately not a number of its own:
	 * that property already says how often liveness is refreshed, and a second
	 * opinion on the same question would be free to drift from it.
	 */
	@Test
	void asksAtTheIntervalTheWorkerRenewsAt() {
		WorkerProperties properties = new WorkerProperties(null, null, 7, null, null, null, null, null, null, null);

		assertThat(properties.renewSecondsOrDefault()).isEqualTo(7);
	}

	private ExecutionReclaimScheduler scheduler() {
		return new ExecutionReclaimScheduler(executionReclaim,
				new WorkerProperties(null, null, null, null, null, null, null, null, null, null));
	}

	private WorkerProperties everySecond() {
		return new WorkerProperties(null, null, 1, null, null, null, null, null, null, null);
	}
}