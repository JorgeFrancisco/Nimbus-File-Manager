package br.com.jorgemelo.nimbusfilemanager.processing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.processing.application.dto.Outcome;
import br.com.jorgemelo.nimbusfilemanager.processing.application.dto.Snapshot;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;

class ProcessingCoordinatorTest {

	private ProcessingCoordinator coordinator(int workers, int queueCapacity) {
		return new ProcessingCoordinator(new ProcessingProperties(workers, queueCapacity, 2, 2, 2),
				new ProcessingMetrics());
	}

	@Test
	void preservesInputResultAssociationDespiteOutOfOrderCompletion() {
		ProcessingCoordinator coordinator = coordinator(4, 32);

		try {
			List<Integer> items = IntStream.range(0, 20).boxed().toList();

			// Later items finish first, so completion order is the reverse of input order.
			List<Outcome<Integer, Integer>> outcomes = coordinator.process(items, () -> false, item -> {
				Thread.sleep((20 - item) % 7);

				return item * 10;
			});

			assertThat(outcomes).hasSize(20);

			for (int i = 0; i < 20; i++) {
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

			coordinator.process(items, () -> false, item -> item, done -> {
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
		ProcessingMetrics metrics = new ProcessingMetrics();

		ProcessingCoordinator coordinator = new ProcessingCoordinator(new ProcessingProperties(2, 8, 2, 2, 2), metrics);

		try {
			// A 2-party barrier only trips if two workers run at the same time; if the
			// pool were serial, await() would time out and produce error outcomes.
			CyclicBarrier barrier = new CyclicBarrier(2);

			List<Outcome<Integer, Integer>> outcomes = coordinator.process(List.of(1, 2, 3, 4), () -> false, item -> {
				barrier.await(5, TimeUnit.SECONDS);

				return item;
			});

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

			List<Integer> items = List.of(0, 1, 2);

			Thread runner = new Thread(() -> coordinator.process(items, () -> false, item -> {
				started.incrementAndGet();
				gate.await(5, TimeUnit.SECONDS);

				return item;
			}));
			runner.setDaemon(true);
			runner.start();

			// workers(1) + queue(1) = 2 admitted; the 3rd submit must block on
			// backpressure,
			// so only one task has actually started and the submit loop is still running.
			Thread.sleep(300);

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
			});

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
			}));
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
			});

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

		coordinator.process(List.of(1, 2, 3), () -> false, item -> item);

		coordinator.shutdown();

		assertThat(coordinator.isTerminated()).isTrue();
	}

	@Test
	void recordsExecutedCancelledErrorAndTimingMetricsPerOutcome() {
		ProcessingMetrics metrics = new ProcessingMetrics();

		ProcessingCoordinator coordinator = new ProcessingCoordinator(new ProcessingProperties(2, 8, 2, 2, 2), metrics);

		try {
			// Three batches with known outcomes over a shared metrics instance: 3 executed,
			// 2 failed, 4 cancelled before submission.
			coordinator.process(List.of(1, 2, 3), () -> false, item -> item);
			coordinator.process(List.of(4, 5), () -> false, _ -> {
				throw new IllegalStateException("boom");
			});
			coordinator.process(List.of(6, 7, 8, 9), () -> true, item -> item);

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
		ProcessingMetrics metrics = new ProcessingMetrics();

		ProcessingCoordinator coordinator = new ProcessingCoordinator(new ProcessingProperties(2, 8, 2, 2, 2), metrics);

		try {
			List<Outcome<Integer, Integer>> outcomes = coordinator.process(List.of(1), () -> false, _ -> {
				throw new InterruptedException("cancelled mid-task");
			});

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
		ProcessingMetrics metrics = new ProcessingMetrics();

		ProcessingCoordinator coordinator = new ProcessingCoordinator(new ProcessingProperties(1, 1, 2, 2, 2), metrics);

		coordinator.shutdown();

		AtomicInteger progress = new AtomicInteger();

		List<Outcome<Integer, Integer>> outcomes = coordinator.process(List.of(1, 2), () -> false, item -> item,
				progress::set);

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
			});

			assertThat(outcomes.get(0).wasCancelled()).isTrue();
			assertThat(outcomes.get(1).executed()).isTrue();
			assertThat(outcomes.get(1).value()).isEqualTo(2);
		} finally {
			coordinator.shutdown();
		}
	}

	/**
	 * Shutting down from an already-interrupted thread must force the executor
	 * down and hand the interruption back to the caller rather than swallowing it.
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