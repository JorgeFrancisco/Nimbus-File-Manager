package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueueSignals;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * The worker's heartbeat: ask the queue for work, run what it gives, ask again.
 *
 * <p>
 * Concurrency is simply how many of these loops are running. Each one claims,
 * executes and claims again on its own thread, so "three executions at once"
 * means three loops - no semaphore deciding when to let one through, and no
 * chance of asking the queue for work there is nowhere to put. {@code SKIP
 * LOCKED} makes the loops harmless to each other: two claiming at the same
 * instant get different rows, or one gets nothing.
 *
 * <p>
 * An idle loop waits on the notification channel instead of sleeping through
 * its whole interval, and that is the only thing the signal does: it shortens a
 * wait. It never says which execution to run, and it is never required - when
 * nothing signals, the wait ends on its own and the loop asks the queue exactly
 * as it always did. That is why the interval is still measured in seconds
 * rather than being pushed up now that something faster exists: it is the net,
 * and a net that is only tested when the fast path fails should not be made
 * thinner on the strength of the fast path working.
 */
@Slf4j
@Component
@Profile(NimbusProfiles.WORKER)
public class WorkerLoop {

	private final ExecutionDispatcher executionDispatcher;
	private final LeaseRenewer leaseRenewer;
	private final ExecutionQueueSignals executionQueueSignals;
	private final WorkerProperties workerProperties;

	private final ExecutorService loops;
	private final ScheduledExecutorService renewals;

	private volatile boolean accepting = true;

	public WorkerLoop(ExecutionDispatcher executionDispatcher, LeaseRenewer leaseRenewer,
			ExecutionQueueSignals executionQueueSignals, WorkerProperties workerProperties) {
		this.executionDispatcher = executionDispatcher;
		this.leaseRenewer = leaseRenewer;
		this.executionQueueSignals = executionQueueSignals;
		this.workerProperties = workerProperties;
		this.loops = Executors.newVirtualThreadPerTaskExecutor();
		this.renewals = Executors.newSingleThreadScheduledExecutor(this::renewerThread);
	}

	public void start() {
		int seconds = workerProperties.renewSecondsOrDefault();

		renewals.scheduleWithFixedDelay(this::renewQuietly, seconds, seconds, TimeUnit.SECONDS);

		for (int loop = 0; loop < workerProperties.maxConcurrentOrDefault(); loop++) {
			loops.execute(this::claimUntilStopped);
		}
	}

	/**
	 * Stops taking new work. Whatever is already running keeps its lease and is
	 * given time to finish; PENDING rows are left exactly as they are, because a
	 * request nobody started is not this worker's to interrupt.
	 */
	public void stopAccepting() {
		accepting = false;
	}

	public boolean isAccepting() {
		return accepting;
	}

	/**
	 * The signal count is read <em>before</em> asking the queue, so that a
	 * notification which arrives while that question is in flight still counts as
	 * new when the loop comes to wait. Reading it afterwards would swallow exactly
	 * the notification for the work that was queued a moment too late to be seen -
	 * and that execution would then wait out the whole polling interval.
	 */
	private void claimUntilStopped() {
		while (accepting) {
			long seen = executionQueueSignals.signalCount();

			if (!dispatchQuietly()) {
				waitForWorkOrTimeout(seen);
			}
		}
	}

	private boolean dispatchQuietly() {
		try {
			return executionDispatcher.dispatchOne();
		} catch (RuntimeException exception) {
			log.error("Could not take work from the queue", exception);

			return false;
		}
	}

	private void renewQuietly() {
		try {
			leaseRenewer.renew();
		} catch (RuntimeException exception) {
			// Losing the database is not this thread's problem to solve: the leases
			// expire, recovery takes the work back, and saying so every round would fill
			// the log with one repeated line.
			log.debug("Lease renewal round failed", exception);
		}
	}

	/**
	 * Ends early when something is queued, and on its own when nothing is. Both
	 * endings lead to the same next step - ask the queue - so the loop never has
	 * to know which of the two happened.
	 */
	private void waitForWorkOrTimeout(long seen) {
		executionQueueSignals.awaitSignalAfter(seen, Duration.ofSeconds(workerProperties.pollSecondsOrDefault()));

		if (Thread.currentThread().isInterrupted()) {
			accepting = false;
		}
	}

	private Thread renewerThread(Runnable runnable) {
		Thread thread = new Thread(runnable, "nimbus-lease-renewer");

		thread.setDaemon(true);

		return thread;
	}

	@PreDestroy
	void shutdown() {
		stopAccepting();

		renewals.shutdownNow();
		loops.shutdownNow();
	}
}