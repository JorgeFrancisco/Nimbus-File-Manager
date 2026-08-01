package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;

/**
 * Tracks which in-progress executions have been asked to stop. The inventory
 * batch job, OrganizationPlanner and OrganizationExecutor each register their
 * executionId when their work starts and check {@link #isCancelled(Long)}
 * periodically; the "Cancelar" button on the progress screen calls
 * {@link #requestCancellation(Long)} from the HTTP request thread to flip that
 * flag.
 *
 * <p>
 * This is intentionally in-memory only (no DB flag): cancellation only makes
 * sense for an execution whose background thread is still alive in this JVM,
 * and the map entry disappears the moment that thread finishes, so there's
 * nothing to reconcile after a restart.
 */
@Service
public class ExecutionCancellationService {

	private final ConcurrentHashMap<Long, AtomicBoolean> flags = new ConcurrentHashMap<>();

	public void register(Long executionId) {
		if (executionId != null) {
			flags.put(executionId, new AtomicBoolean(false));
		}
	}

	public void unregister(Long executionId) {
		if (executionId != null) {
			flags.remove(executionId);
		}
	}

	/**
	 * @return true if a running execution was found and cancellation was requested;
	 * false if there is nothing running for that id (already finished, or never
	 * started).
	 */
	public boolean requestCancellation(Long executionId) {
		AtomicBoolean flag = executionId == null ? null : flags.get(executionId);

		if (flag == null) {
			return false;
		}

		flag.set(true);

		return true;
	}

	public int requestAllCancellations() {
		flags.values().forEach(flag -> flag.set(true));

		return flags.size();
	}

	public boolean isCancelled(Long executionId) {
		AtomicBoolean flag = executionId == null ? null : flags.get(executionId);

		return flag != null && flag.get();
	}

	/**
	 * Whether a background thread for this execution is still alive in this JVM
	 * (registered its id and has not finished). Used to avoid marking a
	 * genuinely-running execution as INTERRUPTED: the map is in-memory only, so
	 * after a restart it is empty and every stale execution is correctly orphaned.
	 */
	public boolean isLive(Long executionId) {
		return executionId != null && flags.containsKey(executionId);
	}
}