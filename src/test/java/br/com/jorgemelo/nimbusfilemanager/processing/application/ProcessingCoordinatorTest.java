package br.com.jorgemelo.nimbusfilemanager.processing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.processing.application.dto.Outcome;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionMetricsContext;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ProcessingMetrics;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.Snapshot;

class ProcessingCoordinatorTest {

	/** This test's own accumulator: nothing here is shared with another run. */
	private final ProcessingMetrics metrics = new ExecutionMetricsContext().processing();

	private ProcessingCoordinator coordinator(int workers, int queueCapacity) {
		return new ProcessingCoordinator(new ProcessingProperties(workers, queueCapacity, 2, 2, 2, 1));
	}

	/**
	 * Results stay with the item they came from even when the items finish in a
	 * different order from the one they were handed over in.
	 *
	 * <p>
	 * The disorder is arranged rather than hoped for. It used to be a sleep whose
	 * length varied with the item, which made the completion order a matter of how
	 * loaded the machine was - and the comment claimed a reversal the arithmetic
	 * did not actually produce. Here the order is imposed:
	 * <ul>
	 * <li>the pool has {@code WORKERS} threads, so the first {@code WORKERS} items
	 * are the ones running, and a barrier holds them until all of them have
	 * arrived - which both proves they are running at once and keeps each group
	 * from mixing with the next;</li>
	 * <li>inside a group, each item waits for the item after it, so the group
	 * finishes downwards and the very first result to land is the last item of the
	 * group.</li>
	 * </ul>
	 * The completion order is therefore a fixed sequence and is asserted as one.
	 *
	 * <p>
	 * It cannot deadlock: nothing waits on anything outside its own group, the
	 * highest item of each group waits for nobody, and the item count is a
	 * multiple of the worker count so no group is ever left short of an arrival.
	 * Every wait is bounded anyway, so a coordinator that ran fewer threads than
	 * it was configured for would fail this test instead of hanging the suite.
	 */
	@Test
	void preservesInputResultAssociationDespiteOutOfOrderCompletion() {
		int workers = 4;
		int size = 20;

		ProcessingCoordinator coordinator = coordinator(workers, 32);

		try {
			List<Integer> items = IntStream.range(0, size).boxed().toList();

			CyclicBarrier group = new CyclicBarrier(workers);

			CountDownLatch[] finished = IntStream.range(0, size).mapToObj(_ -> new CountDownLatch(1))
					.toArray(CountDownLatch[]::new);

			Queue<Integer> completed = new ConcurrentLinkedQueue<>();

			List<Outcome<Integer, Integer>> outcomes = coordinator.process(items, () -> false, item -> {
				group.await(10, TimeUnit.SECONDS);

				int next = item + 1;

				// Everything but the last of the group waits for the one above it.
				if (next % workers != 0) {
					assertThat(finished[next].await(10, TimeUnit.SECONDS)).isTrue();
				}

				completed.add(item);

				finished[item].countDown();

				return item * 10;
			}, metrics);

			assertThat(completed).as("each group of four finished downwards, so nothing finished in input order")
					.containsExactly(3, 2, 1, 0, 7, 6, 5, 4, 11, 10, 9, 8, 15, 14, 13, 12, 19, 18, 17, 16);

			assertThat(outcomes).hasSize(size);

			for (int i = 0; i < size; i++) {
				assertThat(outcomes.get(i).item()).isEqualTo(i);
				assertThat(outcomes.get(i).executed()).isTrue();
				assertThat(outcomes.get(i).value()).isEqualTo(i * 10);
			}
		} finally {
			coordinator.shutdown();
		}
	}

	@Test
	void reportsCompletionProgressExactlyOncePerItem() {
		ProcessingCoordinator coordinator = coordinator(4, 32);

		try {
			List<Integer> items = IntStream.range(0, 50).boxed().toList();

			AtomicInteger callbackCount = new AtomicInteger();
			AtomicInteger maxReported = new AtomicInteger();

			coordinator.process(items, () -> false, item -> item, metrics, done -> {
				callbackCount.incrementAndGet();
				maxReported.accumulateAndGet(done, Math::max);
			});

			assertThat(callbackCount.get()).isEqualTo(50);
			assertThat(maxReported.get()).isEqualTo(50);
		} finally {
			coordinator.shutdown();
		}
	}

	@Test
	void runsTasksConcurrentlyUpToTheWorkerLimit() {
		ProcessingCoordinator coordinator = new ProcessingCoordinator(new ProcessingProperties(2, 8, 2, 2, 2, 1));

		try {
			// A 2-party barrier only trips if two workers run at the same time; if the
			// pool were serial, await() would time out and produce error outcomes.
			CyclicBarrier barrier = new CyclicBarrier(2);

			List<Outcome<Integer, Integer>> outcomes = coordinator.process(List.of(1, 2, 3, 4), () -> false, item -> {
				barrier.await(5, TimeUnit.SECONDS);

				return item;
			}, metrics);

			assertThat(outcomes).hasSize(4).allMatch(Outcome::executed);
			assertThat(metrics.snapshot().maxConcurrency()).isGreaterThanOrEqualTo(2);
		} finally {
			coordinator.shutdown();
		}
	}

	@Test
	void appliesBackpressureInsteadOfCreatingUnboundedTasks() throws Exception {
		ProcessingCoordinator coordinator = coordinator(1, 1);

		try {
			CountDownLatch gate = new CountDownLatch(1);

			AtomicInteger started = new AtomicInteger();

			CountDownLatch first = new CountDownLatch(1);

			List<Integer> items = List.of(0, 1, 2);

			Thread runner = new Thread(() -> coordinator.process(items, () -> false, item -> {
				started.incrementAndGet();
				first.countDown();
				gate.await(5, TimeUnit.SECONDS);

				return item;
			}, metrics));
			runner.setDaemon(true);
			runner.start();

			// Waiting for the task to signal instead of sleeping a guessed delay: on a
			// loaded machine the worker can take longer to be scheduled, and this test is
			// about backpressure, not about scheduling latency.
			assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();

			// workers(1) + queue(1) = 2 admitted; the 3rd submit must block on
			// backpressure. With a single worker held by the first task, no second task
			// can have started, and the submit loop is still running.
			assertThat(started.get()).isEqualTo(1);
			assertThat(runner.isAlive()).isTrue();

			gate.countDown();
			runner.join(5_000);

			assertThat(runner.isAlive()).isFalse();
			assertThat(started.get()).isEqualTo(3);
		} finally {
			coordinator.shutdown();
		}
	}

	@Test
	void cancellationBeforeSubmissionSkipsEveryItem() {
		ProcessingCoordinator coordinator = coordinator(2, 8);

		try {
			AtomicInteger workerCalls = new AtomicInteger();

			List<Outcome<Integer, Integer>> outcomes = coordinator.process(List.of(0, 1, 2, 3), () -> true, item -> {
				workerCalls.incrementAndGet();

				return item;
			}, metrics);

			assertThat(outcomes).hasSize(4).allMatch(Outcome::wasCancelled);
			assertThat(workerCalls.get()).isZero();
		} finally {
			coordinator.shutdown();
		}
	}

	@Test
	void cancellationSkipsQueuedTasksThatHaveNotStarted() throws Exception {
		ProcessingCoordinator coordinator = coordinator(1, 8);

		try {
			CountDownLatch firstRunning = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);

			AtomicInteger started = new AtomicInteger();

			AtomicBoolean cancelled = new AtomicBoolean(false);

			List<Integer> items = List.of(0, 1, 2);

			@SuppressWarnings("unchecked")
			List<Outcome<Integer, Integer>>[] holder = new List[1];

			Thread runner = new Thread(() -> holder[0] = coordinator.process(items, cancelled::get, item -> {
				started.incrementAndGet();

				if (item == 0) {
					firstRunning.countDown();
					release.await(5, TimeUnit.SECONDS);
				}

				return item;
			}, metrics));
			runner.setDaemon(true);
			runner.start();

			// Task 0 is running (tasks 1 and 2 are queued behind the single worker).
			assertThat(firstRunning.await(5, TimeUnit.SECONDS)).isTrue();

			cancelled.set(true);
			release.countDown();
			runner.join(5_000);

			assertThat(holder[0].get(0).executed()).isTrue();
			assertThat(holder[0].get(1).wasCancelled()).isTrue();
			assertThat(holder[0].get(2).wasCancelled()).isTrue();
			// The cancelled tasks never entered the worker body.
			assertThat(started.get()).isEqualTo(1);
		} finally {
			coordinator.shutdown();
		}
	}

	@Test
	void isolatesASingleTaskFailure() {
		ProcessingCoordinator coordinator = coordinator(3, 16);

		try {
			List<Outcome<Integer, Integer>> outcomes = coordinator.process(List.of(0, 1, 2), () -> false, item -> {
				if (item == 1) {
					throw new IllegalStateException("boom");
				}

				return item;
			}, metrics);

			assertThat(outcomes.get(0).executed()).isTrue();
			assertThat(outcomes.get(1).failed()).isTrue();
			assertThat(outcomes.get(1).error()).hasMessage("boom");
			assertThat(outcomes.get(2).executed()).isTrue();
		} finally {
			coordinator.shutdown();
		}
	}

	@Test
	void shutdownTerminatesThePoolWithoutOrphanThreads() {
		ProcessingCoordinator coordinator = coordinator(2, 8);

		coordinator.process(List.of(1, 2, 3), () -> false, item -> item, metrics);

		coordinator.shutdown();

		assertThat(coordinator.isTerminated()).isTrue();
	}

	@Test
	void recordsExecutedCancelledErrorAndTimingMetricsPerOutcome() {
		ProcessingCoordinator coordinator = new ProcessingCoordinator(new ProcessingProperties(2, 8, 2, 2, 2, 1));

		try {
			// Three batches with known outcomes over a shared metrics instance: 3 executed,
			// 2 failed, 4 cancelled before submission.
			coordinator.process(List.of(1, 2, 3), () -> false, item -> item, metrics);
			coordinator.process(List.of(4, 5), () -> false, _ -> {
				throw new IllegalStateException("boom");
			}, metrics);
			coordinator.process(List.of(6, 7, 8, 9), () -> true, item -> item, metrics);

			Snapshot snapshot = metrics.snapshot();

			assertThat(snapshot.tasksExecuted()).isEqualTo(3);
			assertThat(snapshot.tasksError()).isEqualTo(2);
			assertThat(snapshot.tasksCancelled()).isEqualTo(4);
			// The 5 tasks that actually ran each accumulate a positive queue-wait and total
			// time; asserting the sum is positive is stable (it never depends on a single
			// tiny nanoTime delta) and proves the timings are recorded, not dropped.
			assertThat(snapshot.queueWaitNanos()).isPositive();
			assertThat(snapshot.taskTotalNanos()).isPositive();
		} finally {
			coordinator.shutdown();
		}
	}

	@Test
	void mapsAnInterruptedWorkerToACancelledOutcomeNotAnError() {
		// Functional guarantee: when a worker is interrupted (a batch cancelled/shut
		// down
		// mid-flight), the coordinator must classify it as CANCELLED, never as an
		// ERROR, so a
		// user-initiated cancellation is not surfaced to callers as a spurious failure.
		// The
		// worker throws InterruptedException directly, so the test is fully
		// deterministic and
		// does not depend on real thread-interruption timing.
		ProcessingCoordinator coordinator = new ProcessingCoordinator(new ProcessingProperties(2, 8, 2, 2, 2, 1));

		try {
			List<Outcome<Integer, Integer>> outcomes = coordinator.process(List.of(1), () -> false, _ -> {
				throw new InterruptedException("cancelled mid-task");
			}, metrics);

			assertThat(outcomes.get(0).wasCancelled()).isTrue();
			assertThat(outcomes.get(0).failed()).isFalse();
			assertThat(metrics.snapshot().tasksCancelled()).isEqualTo(1);
			assertThat(metrics.snapshot().tasksError()).isZero();
		} finally {
			coordinator.shutdown();
		}
	}

	/**
	 * A coordinator whose executor is already shut down rejects every submission.
	 * The batch must still come back complete - one error outcome per item, the
	 * backpressure permit released and progress reported - instead of blowing up
	 * the caller or leaking a permit and deadlocking the next batch.
	 */
	@Test
	void submissionRejectedByAShutDownExecutorBecomesAnErrorOutcome() {
		ProcessingCoordinator coordinator = new ProcessingCoordinator(new ProcessingProperties(1, 1, 2, 2, 2, 1));

		coordinator.shutdown();

		AtomicInteger progress = new AtomicInteger();

		List<Outcome<Integer, Integer>> outcomes = coordinator.process(List.of(1, 2), () -> false, item -> item,
				metrics, progress::set);

		assertThat(outcomes).hasSize(2).allMatch(Outcome::failed);
		assertThat(outcomes.get(0).error()).isInstanceOf(RejectedExecutionException.class);
		assertThat(progress).hasValue(2);
		assertThat(metrics.snapshot().tasksError()).isEqualTo(2);
	}

	/**
	 * {@code execute} only captures {@link Exception}, so an {@link Error} escapes
	 * the task and surfaces at {@code future.get()} with the slot never filled.
	 * That single item comes back cancelled and the rest of the batch still
	 * completes - one broken worker never aborts the run.
	 */
	@Test
	void anErrorThrownByAWorkerLeavesThatItemCancelledWithoutAbortingTheBatch() {
		ProcessingCoordinator coordinator = coordinator(2, 8);

		try {
			List<Outcome<Integer, Integer>> outcomes = coordinator.process(List.of(1, 2), () -> false, item -> {
				if (item == 1) {
					throw new StackOverflowError("worker blew the stack");
				}

				return item;
			}, metrics);

			assertThat(outcomes.get(0).wasCancelled()).isTrue();
			assertThat(outcomes.get(1).executed()).isTrue();
			assertThat(outcomes.get(1).value()).isEqualTo(2);
		} finally {
			coordinator.shutdown();
		}
	}

	/**
	 * Shutting down from an already-interrupted thread must force the executor down
	 * and hand the interruption back to the caller rather than swallowing it.
	 */
	@Test
	void shutdownOnAnInterruptedThreadForcesTerminationAndKeepsTheInterruptFlag() {
		ProcessingCoordinator coordinator = coordinator(1, 1);

		Thread.currentThread().interrupt();

		try {
			coordinator.shutdown();

			assertThat(Thread.currentThread().isInterrupted()).isTrue();
			assertThat(coordinator.isTerminated()).isTrue();
		} finally {
			Thread.interrupted();
		}
	}
}