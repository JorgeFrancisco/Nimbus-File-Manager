package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.AdvisoryPathLockRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;

/**
 * Stops two operations from working on the same files at the same time.
 *
 * <p>
 * The rule has not changed - an operation owns the paths it was given, plus
 * everything under them - but where it is enforced has. It used to be a map
 * guarded by {@code synchronized}, which is exactly as wide as one JVM. With
 * the work moving to a process of its own, a lock only one of the two processes
 * can see is no lock at all: the Explorer would rename a file an organization
 * was in the middle of moving, and neither side would ever know.
 *
 * <p>
 * So exclusion lives in PostgreSQL now, as session advisory locks over the
 * hashed path chain from {@link OperationPathKey}. What callers see is
 * unchanged: acquire, optionally wait a while, ask whether a tree is busy, and
 * close.
 */
@Service
public class OperationLockService {

	private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

	/**
	 * The session this thread is already holding locks in, if any.
	 *
	 * <p>
	 * Nesting is real and predates this change: {@code OrganizationService} takes
	 * the lock and then calls {@code OrganizationExecutor}, which takes the same
	 * paths again. The old in-memory lock allowed it by ignoring conflicts from
	 * the owning thread. Reusing the session restores exactly that, and gets it
	 * from PostgreSQL rather than from a special case - advisory locks are
	 * reentrant within a session, so the inner acquire simply succeeds. The
	 * connection is handed back when the outermost lock closes.
	 */
	private final ThreadLocal<NestedLockSession> currentSession = new ThreadLocal<>();

	private final AdvisoryPathLockRepository advisoryPathLockRepository;

	public OperationLockService(AdvisoryPathLockRepository advisoryPathLockRepository) {
		this.advisoryPathLockRepository = advisoryPathLockRepository;
	}

	public OperationLock acquire(ExecutionType executionType, Path... paths) {
		return acquireWithin(Duration.ZERO, executionType, paths);
	}

	/**
	 * Same as {@link #acquire}, but waits up to {@code timeout} for a conflicting
	 * operation to finish before refusing.
	 *
	 * <p>
	 * Polling rather than blocking inside the database, deliberately:
	 * {@code pg_advisory_lock} waits with no deadline, and a background pass that
	 * waits forever is a connection held forever. Retrying keeps the caller's
	 * deadline where the caller can see it.
	 */
	public OperationLock acquireWithin(Duration timeout, ExecutionType executionType, Path... paths) {
		List<Path> requested = requestedPaths(paths);

		Set<PathLockKey> keys = OperationPathKey.chainOf(requested);

		long deadline = System.nanoTime() + timeout.toNanos();

		NestedLockSession nested = currentSession.get();

		boolean outermost = nested == null;

		Connection session = outermost ? openSession() : nested.connection();

		try {
			while (!advisoryPathLockRepository.tryLockAll(session, keys)) {
				awaitRelease(deadline - System.nanoTime(), executionType, requested);
			}

			if (outermost) {
				currentSession.set(new NestedLockSession(session));
			} else {
				nested.enter();
			}

			return new OperationLock(executionType, displayPath(requested), session, keys, this::release);
		} catch (SQLException exception) {
			releaseWhenOutermost(session, outermost);

			throw lockFailure(exception);
		} catch (RuntimeException exception) {
			releaseWhenOutermost(session, outermost);

			throw exception;
		}
	}

	/**
	 * Takes the paths on behalf of one execution, and ties the two together.
	 *
	 * <p>
	 * The tie is the point. A lock on its own can be lost silently; an execution
	 * on its own goes on being renewed as though nothing happened. Held as one
	 * thing, losing the session is something the execution and its lease both find
	 * out about.
	 */
	public ExecutionOwnership acquireFor(long executionId, ExecutionType executionType, Path... paths) {
		return new ExecutionOwnership(executionId, acquire(executionType, paths), this);
	}

	/**
	 * Ownership for work that holds no tree, because its work is a query rather
	 * than a folder - draining a backlog, grouping what is already fingerprinted,
	 * deleting catalog rows past their retention.
	 *
	 * <p>
	 * It takes no lock and it excludes nobody, which is the whole point: making
	 * such an execution invent a path would have it wait for - and block - work it
	 * has nothing to do with. What it still gives is the shape everything else
	 * has, so the lease is renewed and the handler asks the same question; it
	 * simply always gets yes.
	 *
	 * <p>
	 * Who may use this is not the caller's choice: the handler declares it through
	 * {@link ExecutionJobHandler#requiresPathLock()}, and a handler that can reach
	 * the port that changes the user's files may not declare it.
	 */
	public ExecutionOwnership acquireNothingFor(long executionId) {
		return new ExecutionOwnership(executionId, null, this);
	}

	/**
	 * Non-throwing check used by background coordination (the folder watcher) to
	 * decide whether to even start a reconcile/inventory this cycle. It answers
	 * for every process now, rather than only for this one.
	 */
	public boolean isBusy(Path... paths) {
		try {
			return advisoryPathLockRepository.lockedByAnyone(OperationPathKey.chainOf(requestedPaths(paths)));
		} catch (SQLException exception) {
			throw lockFailure(exception);
		}
	}

	/**
	 * Whether the caller still owns what it took. A restarted server drops every
	 * advisory lock without telling anyone, and an operation that keeps moving
	 * files on the strength of a lock it no longer holds is the one way two
	 * processes end up writing the same file.
	 */
	public boolean stillHolds(OperationLock lock) {
		try {
			return advisoryPathLockRepository.stillHolds(lock.session(), lock.keys());
		} catch (SQLException _) {
			// An unreachable database is indistinguishable from lost ownership, and both
			// deserve the same answer: stop touching files.
			return false;
		}
	}

	/**
	 * The type in the message is the one that <em>waited</em>, never the one that
	 * is holding: an advisory lock says a key is taken, not by whom. It used to
	 * read "another RECONCILE is already running", which was a guess presented as a
	 * fact - the holder was as likely to be the inventory over the same drive.
	 */
	private void awaitRelease(long remainingNanos, ExecutionType executionType, List<Path> requested) {
		if (remainingNanos <= 0) {
			throw new OperationLockException("This " + executionType
					+ " is waiting for another execution to release the path: " + displayPath(requested));
		}

		try {
			TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, POLL_INTERVAL.toNanos()));
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();

			throw new OperationLockException("Interrupted while waiting for the " + executionType
					+ " execution holding path: " + displayPath(requested));
		}
	}

	/**
	 * Releases the locks and hands the connection back. Unlocking explicitly
	 * matters as much as closing: a pooled connection returned while still holding
	 * an advisory lock would carry it into whatever borrows it next.
	 */
	private void release() {
		NestedLockSession nested = currentSession.get();

		if (nested == null || nested.leave()) {
			return;
		}

		currentSession.remove();

		try (Connection session = nested.connection()) {
			advisoryPathLockRepository.unlockAll(session);
		} catch (SQLException _) {
			// The connection is going away either way, and the server releases every lock
			// of a session it loses - so there is nothing left here to repair or report.
		}
	}

	private void releaseWhenOutermost(Connection session, boolean outermost) {
		if (outermost) {
			closeQuietly(session);
		}
	}

	private Connection openSession() {
		try {
			return advisoryPathLockRepository.openLockSession();
		} catch (SQLException exception) {
			throw lockFailure(exception);
		}
	}

	private void closeQuietly(Connection session) {
		try {
			session.close();
		} catch (SQLException _) {
			// Already failing; the pool discards a connection it cannot take back.
		}
	}

	private List<Path> requestedPaths(Path[] paths) {
		List<Path> requested = new ArrayList<>();

		for (Path path : paths) {
			if (path != null) {
				requested.add(path);
			}
		}

		if (requested.isEmpty()) {
			throw new IllegalArgumentException("At least one path is required to acquire an operation lock.");
		}

		return requested;
	}

	private String displayPath(List<Path> requested) {
		return requested.getFirst().toString();
	}

	private OperationLockException lockFailure(SQLException exception) {
		return new OperationLockException(
				"Could not reach the database to coordinate file operations: " + exception.getMessage());
	}
}