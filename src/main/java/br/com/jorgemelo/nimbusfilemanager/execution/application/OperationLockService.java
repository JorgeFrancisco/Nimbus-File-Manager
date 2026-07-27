package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

@Service
public class OperationLockService {

	private final Map<String, OperationLock> activeLocks = new ConcurrentHashMap<>();

	public OperationLock acquire(ExecutionType executionType, Path... paths) {
		return acquireWithin(Duration.ZERO, executionType, paths);
	}

	/**
	 * Same as {@link #acquire}, but waits up to {@code timeout} for a conflicting
	 * lock to be released instead of failing on the first look.
	 *
	 * <p>
	 * This exists for work the user asked for. Background maintenance that loses
	 * the lock simply retries on its next pass, so it must not wait; a batch the
	 * user started has nobody to retry it, and refusing it because a scheduled
	 * pass happened to start seconds earlier makes the application look broken for
	 * something the user cannot see. Waiting weakens no guarantee - the lock is
	 * still exclusive, and every path is still checked before it is granted.
	 */
	public OperationLock acquireWithin(Duration timeout, ExecutionType executionType, Path... paths) {
		Set<String> requestedPaths = normalizedPaths(paths);

		synchronized (activeLocks) {
			long deadline = System.nanoTime() + timeout.toNanos();
			long currentThreadId = Thread.currentThread().threadId();

			OperationLock conflict = conflicting(requestedPaths, currentThreadId);

			while (conflict != null) {
				awaitRelease(deadline - System.nanoTime(), conflict);

				conflict = conflicting(requestedPaths, currentThreadId);
			}

			String lockId = UUID.randomUUID().toString();

			OperationLock lock = new OperationLock(lockId, currentThreadId, executionType, requestedPaths,
					() -> release(lockId));

			activeLocks.put(lock.id(), lock);

			return lock;
		}
	}

	/**
	 * Waits for {@code release} to signal, or refuses once the deadline has
	 * passed. The caller holds the monitor, which {@code timedWait} releases while
	 * it waits, so a holder on another thread can finish and hand the lock over.
	 */
	private void awaitRelease(long remainingNanos, OperationLock conflict) {
		if (remainingNanos <= 0) {
			throw new OperationLockException("Another " + conflict.executionType()
					+ " execution is already running for path: " + conflict.displayPath());
		}

		try {
			TimeUnit.NANOSECONDS.timedWait(activeLocks, remainingNanos);
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();

			throw new OperationLockException("Interrupted while waiting for the " + conflict.executionType()
					+ " execution holding path: " + conflict.displayPath());
		}
	}

	private OperationLock conflicting(Set<String> requestedPaths, long currentThreadId) {
		return activeLocks.values().stream()
				.filter(lock -> lock.ownerThreadId() != currentThreadId && lock.conflictsWith(requestedPaths))
				.findFirst().orElse(null);
	}

	/**
	 * Non-throwing check used by background coordination (the folder watcher) to
	 * decide whether to even start a reconcile/inventory this cycle. Returns true
	 * when another thread already holds a lock that conflicts with {@code paths}.
	 * Same-thread reentrancy is ignored, mirroring {@link #acquire}.
	 */
	public boolean isBusy(Path... paths) {
		Set<String> requestedPaths = normalizedPaths(paths);

		synchronized (activeLocks) {
			long currentThreadId = Thread.currentThread().threadId();

			return activeLocks.values().stream()
					.anyMatch(lock -> lock.ownerThreadId() != currentThreadId && lock.conflictsWith(requestedPaths));
		}
	}

	private Set<String> normalizedPaths(Path[] paths) {
		Set<String> normalized = new LinkedHashSet<>();

		for (Path path : paths) {
			if (path != null) {
				normalized.add(PathUtils.normalize(path).toLowerCase(Locale.ROOT));
			}
		}

		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("At least one path is required to acquire an operation lock.");
		}

		return normalized;
	}

	private void release(String lockId) {
		synchronized (activeLocks) {
			activeLocks.remove(lockId);

			// Wakes whoever is waiting in acquireWithin: without this the waiter would
			// sit until its own deadline even though the path is already free.
			activeLocks.notifyAll();
		}
	}
}