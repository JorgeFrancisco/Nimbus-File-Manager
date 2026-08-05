package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;

/**
 * Whether an execution has been asked to stop.
 *
 * <p>
 * This used to be a map of flags in this JVM, on the reasoning that
 * cancellation only made sense while the thread doing the work was alive here.
 * That stops being true the moment the work runs in another process: the button
 * is pressed in one JVM and has to be seen in another, so the request lives in
 * the {@code execution} row and nowhere else.
 *
 * <p>
 * Read through a short-lived cache because the check sits in tight loops - once
 * per file, in places - and a query per file would be a query per file. A
 * request is honoured within {@link #FRESHNESS}, which is far below what anyone
 * waiting for a cancel would notice, and the cache holds no negative answer
 * longer than that.
 */
@Service
public class ExecutionCancellationService {

	private static final Duration FRESHNESS = Duration.ofMillis(500);

	private final Map<Long, CancellationAnswer> answers = new ConcurrentHashMap<>();

	private final ExecutionQueue executionQueue;
	private final Clock clock;

	public ExecutionCancellationService(ExecutionQueue executionQueue, Clock clock) {
		this.executionQueue = executionQueue;
		this.clock = clock;
	}

	/**
	 * Drops the cached answer for an execution that has ended.
	 *
	 * <p>
	 * Housekeeping, not state: without it a worker running for days would keep one
	 * entry per execution it had ever run. Nothing decides anything by whether an
	 * id is in the cache - the row decides, and the cache only says how recently it
	 * was asked.
	 */
	public void forget(Long executionId) {
		if (executionId != null) {
			answers.remove(executionId);
		}
	}

	/**
	 * @return true if a running execution was found and cancellation was requested;
	 * false if there is nothing running for that id (already finished, or never
	 * started).
	 */
	public boolean requestCancellation(Long executionId) {
		if (executionId == null || !executionQueue.requestCancel(executionId)) {
			return false;
		}

		// The asker is usually not the runner, so nothing here can act on the request
		// - but a stale "not cancelled" in this JVM's cache would delay it needlessly.
		answers.remove(executionId);

		return true;
	}

	/**
	 * Asks everything in flight to stop, wherever it is running.
	 *
	 * <p>
	 * This used to walk the set of executions started by this process, which was
	 * true while there was only one. With the work in another JVM it meant an
	 * administrative operation - changing the monitored library - asking the
	 * database whether anything was still active, seeing the worker's inventory,
	 * and cancelling nothing, because the worker's execution was in the worker's
	 * memory. It waited for something it had not asked to stop, and gave up.
	 */
	public int requestAllCancellations() {
		answers.clear();

		return executionQueue.requestCancelOfEverything();
	}

	public boolean isCancelled(Long executionId) {
		if (executionId == null) {
			return false;
		}

		Instant now = clock.instant();

		CancellationAnswer cached = answers.get(executionId);

		if (cached != null && cached.isFresh(now, FRESHNESS)) {
			return cached.cancelled();
		}

		boolean cancelled = executionQueue.isCancelRequested(executionId);

		answers.put(executionId, new CancellationAnswer(cancelled, now));

		return cancelled;
	}
}