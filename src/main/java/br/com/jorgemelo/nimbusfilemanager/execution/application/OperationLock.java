package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.sql.Connection;
import java.util.Set;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;

/**
 * Ownership of a set of paths, held for as long as this object is open.
 *
 * <p>
 * It used to carry the paths themselves and the logic deciding whether two sets
 * overlapped, because a map in memory had to answer that question on its own.
 * PostgreSQL answers it now: the ancestors are already part of the key chain,
 * so a lock on a folder and a lock on a file inside it collide without anyone
 * comparing strings. What remains is what the holder still needs - the session
 * the locks live in, so ownership can be re-verified, and the way to let go.
 */
public final class OperationLock implements AutoCloseable {

	private final ExecutionType executionType;
	private final String displayPath;
	private final Connection session;
	private final Set<PathLockKey> keys;
	private final Runnable releaseAction;

	OperationLock(ExecutionType executionType, String displayPath, Connection session, Set<PathLockKey> keys,
			Runnable releaseAction) {
		this.executionType = executionType;
		this.displayPath = displayPath;
		this.session = session;
		this.keys = keys;
		this.releaseAction = releaseAction;
	}

	public ExecutionType executionType() {
		return executionType;
	}

	public String displayPath() {
		return displayPath;
	}

	Connection session() {
		return session;
	}

	Set<PathLockKey> keys() {
		return keys;
	}

	@Override
	public void close() {
		releaseAction.run();
	}
}