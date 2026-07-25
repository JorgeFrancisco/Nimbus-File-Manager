package br.com.jorgemelo.nimbusfilemanager.processing.application;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.processing.application.dto.Outcome;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;
import jakarta.annotation.PreDestroy;

/**
 * Central, reusable coordinator for bounded parallel processing. Today only the
 * inventory uses it, but nothing here is inventory-specific: it takes a list of
 * inputs and a {@link Worker} and runs them across a fixed pool with a bounded
 * queue, returning one {@link Outcome} per input <b>in input order</b>.
 *
 * <p>
 * Guarantees:
 * <ul>
 * <li><b>Bounded:</b> a fixed worker pool plus a bounded queue; the number of
 * in-flight+queued tasks can never exceed {@code workers + queueCapacity}.</li>
 * <li><b>Backpressure, no loss:</b> when saturated, {@link #process} blocks the
 * caller before submitting more work instead of dropping it or creating an
 * unbounded number of tasks.</li>
 * <li><b>Cancellation:</b> once {@code cancelled} turns true, no new task is
 * submitted and any task that has not started yet returns a cancelled outcome.
 * A task already running finishes (external processes are bounded by their own
 * timeout/destroy, handled by the callers).</li>
 * <li><b>Isolation:</b> a worker throwing only fails that input's outcome; the
 * rest continue.</li>
 * <li><b>Association preserved:</b> results are indexed by input position, so
 * out-of-order completion never mixes an input with another's result.</li>
 * <li><b>Clean shutdown:</b> {@link #shutdown()} (also {@code @PreDestroy})
 * drains and terminates the pool without leaking threads.</li>
 * </ul>
 *
 * <p>
 * <b>Contract for callers:</b> the {@link Worker} runs on a pool thread and
 * must not touch JPA — no {@code EntityManager}, managed entity or repository.
 * It must return an immutable/isolated result; persistence stays on the calling
 * thread.
 */
@Component
public class ProcessingCoordinator {

	private static final Logger log = LoggerFactory.getLogger(ProcessingCoordinator.class);
	private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;

	private final ThreadPoolExecutor executor;
	private final Semaphore backpressure;
	private final ProcessingMetrics metrics;
	private final AtomicInteger concurrency = new AtomicInteger();

	public ProcessingCoordinator(ProcessingProperties properties, ProcessingMetrics metrics) {
		this.metrics = metrics;

		int workers = properties.workersOrDefault();
		int queueCapacity = properties.queueCapacityOrDefault();

		AtomicInteger threadCounter = new AtomicInteger();

		// The executor's internal queue is intentionally unbounded: the real bound is
		// the backpressure semaphore below, which is acquired before every submit and
		// released only when the task finishes. It caps in-flight (queued + running)
		// tasks at workers + queueCapacity, so the queue can never hold more than
		// queueCapacity items - without the submit-time rejection race a bounded
		// executor queue would create when a permit is released just before the pool
		// dequeues the next task.
		this.executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
				runnable -> {
					Thread thread = new Thread(runnable, "mm-processing-" + threadCounter.incrementAndGet());
					thread.setDaemon(true);

					return thread;
				});

		this.backpressure = new Semaphore(workers + queueCapacity);

		log.info("ProcessingCoordinator started: workers={}, queueCapacity={}", workers, queueCapacity);
	}

	public <I, O> List<Outcome<I, O>> process(List<I> items, BooleanSupplier cancelled, Worker<I, O> worker) {
		return process(items, cancelled, worker, _ -> {
		});
	}

	/**
	 * Same as {@link #process(List, BooleanSupplier, Worker)}, but calls
	 * {@code onCompleted} once per finished item (success, error or cancel) with
	 * the running completed count, so callers can surface granular progress while a
	 * batch runs. The callback fires from pool threads and may run concurrently, so
	 * it must be thread-safe and cheap - throttle any expensive reporting (e.g.
	 * only act every N items).
	 */
	public <I, O> List<Outcome<I, O>> process(List<I> items, BooleanSupplier cancelled, Worker<I, O> worker,
			IntConsumer onCompleted) {
		int size = items.size();

		AtomicReferenceArray<Outcome<I, O>> slots = new AtomicReferenceArray<>(size);
		AtomicInteger completed = new AtomicInteger();
		Runnable onItemDone = () -> onCompleted.accept(completed.incrementAndGet());

		List<Future<?>> futures = new ArrayList<>(size);

		for (int index = 0; index < size; index++) {
			I item = items.get(index);

			if (cancelled.getAsBoolean()) {
				slots.set(index, Outcome.cancelled(item));

				metrics.incCancelled();
				onItemDone.run();

				continue;
			}

			// Blocks here when workers + queue are full: this is the backpressure. No
			// work is dropped and no unbounded fan-out of tasks is created.
			backpressure.acquireUninterruptibly();

			long submittedAt = System.nanoTime();

			int slot = index;

			try {
				futures.add(
						executor.submit(() -> execute(slot, item, submittedAt, cancelled, worker, slots, onItemDone)));
			} catch (RejectedExecutionException rejected) {
				backpressure.release();

				slots.set(index, Outcome.error(item, rejected));

				metrics.incError();
				onItemDone.run();
			}
		}

		awaitAll(futures);

		return drain(items, slots);
	}

	private <I, O> void execute(int index, I item, long submittedAt, BooleanSupplier cancelled, Worker<I, O> worker,
			AtomicReferenceArray<Outcome<I, O>> slots, Runnable onItemDone) {
		long startedAt = System.nanoTime();

		metrics.recordQueueWait(startedAt - submittedAt);
		metrics.updateMaxConcurrency(concurrency.incrementAndGet());

		try {
			if (cancelled.getAsBoolean()) {
				slots.set(index, Outcome.cancelled(item));

				metrics.incCancelled();

				return;
			}

			O value = worker.apply(item);

			slots.set(index, Outcome.success(item, value));

			metrics.incExecuted();
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();

			slots.set(index, Outcome.cancelled(item));

			metrics.incCancelled();
		} catch (Exception failure) {
			slots.set(index, Outcome.error(item, failure));

			metrics.incError();
		} finally {
			concurrency.decrementAndGet();

			metrics.recordTaskTotal(System.nanoTime() - startedAt);

			backpressure.release();

			onItemDone.run();
		}
	}

	private void awaitAll(List<Future<?>> futures) {
		for (Future<?> future : futures) {
			try {
				future.get();
			} catch (InterruptedException _) {
				Thread.currentThread().interrupt();
			} catch (Exception _) {
				// Every failure mode is already captured as an Outcome by execute();
				// future.get() only rethrows unexpected framework errors, which we don't
				// want to abort the batch.
			}
		}
	}

	private <I, O> List<Outcome<I, O>> drain(List<I> items, AtomicReferenceArray<Outcome<I, O>> slots) {
		List<Outcome<I, O>> results = new ArrayList<>(slots.length());

		for (int index = 0; index < slots.length(); index++) {
			Outcome<I, O> outcome = slots.get(index);

			results.add(outcome != null ? outcome : Outcome.cancelled(items.get(index)));
		}

		return results;
	}

	/** Test/diagnostic aid. */
	public boolean isTerminated() {
		return executor.isTerminated();
	}

	@PreDestroy
	public void shutdown() {
		executor.shutdown();

		try {
			if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();

			executor.shutdownNow();
		}
	}
}