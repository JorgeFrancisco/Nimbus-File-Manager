package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.sql.Connection;

/**
 * The lock session one thread is using, and how many nested acquires are
 * standing on it.
 *
 * <p>
 * Exists because a service that takes the lock and then calls another service
 * that takes the same paths is normal here - organization does it - and the
 * connection may only go back once the outermost of them closes. Advisory locks
 * are reentrant within a session, so the depth is about the connection, not
 * about the locks.
 *
 * <p>
 * Not thread safe, and does not need to be: an instance lives in one thread's
 * {@code ThreadLocal} and is never seen by another.
 */
final class NestedLockSession {

	private final Connection connection;

	private int depth = 1;

	NestedLockSession(Connection connection) {
		this.connection = connection;
	}

	Connection connection() {
		return connection;
	}

	void enter() {
		depth++;
	}

	/**
	 * Marks one nested acquire as closed.
	 *
	 * @return true while an outer lock is still holding the session open
	 */
	boolean leave() {
		depth--;

		return depth > 0;
	}
}