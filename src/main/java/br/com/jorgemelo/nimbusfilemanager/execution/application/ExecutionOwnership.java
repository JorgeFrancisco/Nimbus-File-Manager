package br.com.jorgemelo.nimbusfilemanager.execution.application;

/**
 * One execution's claim on its paths, and the only honest way to ask whether it
 * still holds.
 *
 * <p>
 * The advisory locks live in a session of their own, and PostgreSQL drops every
 * one of them the instant that session goes - a restarted server, a connection
 * reset, a network that blinked. Nothing tells anybody. The lease says nothing
 * about it either, because the lease is a row updated through a different
 * connection: it goes on being renewed, and the execution goes on looking
 * validly owned while it owns nothing at all. That gap is what this closes.
 *
 * <p>
 * It binds the three things that were never bound - the execution id, the lock
 * session, and the renewal - so that losing the session stops the renewal and
 * stops the work, instead of only stopping the locks.
 *
 * <p>
 * Two threads reach it: the one doing the work, at its checkpoints, and the
 * renewer, once a round. They would otherwise use the lock connection at the
 * same time, and a JDBC connection is not shared - so both go through this, one
 * at a time. The cost is nothing: the connection is idle between acquiring and
 * releasing, and this is all anyone asks it.
 */
public final class ExecutionOwnership implements AutoCloseable {

	private final long executionId;

	/**
	 * Null for work that holds no path, which is not the same as work that lost
	 * its locks: there was never a tree to hold. Such an execution still gets an
	 * ownership so that the lease goes on being renewed and the handler asks the
	 * same question everyone else asks - it simply always gets yes.
	 */
	private final OperationLock lock;
	private final OperationLockService operationLockService;

	private boolean released;

	ExecutionOwnership(long executionId, OperationLock lock, OperationLockService operationLockService) {
		this.executionId = executionId;
		this.lock = lock;
		this.operationLockService = operationLockService;
	}

	public long executionId() {
		return executionId;
	}

	/**
	 * Whether the locks this execution took are still held by the session that
	 * took them.
	 *
	 * <p>
	 * A released ownership answers no, which is the useful answer for the renewer:
	 * an execution that has finished is not one whose lease should go on being
	 * extended.
	 */
	public synchronized boolean isStillOwned() {
		if (released) {
			return false;
		}

		return lock == null || operationLockService.stillHolds(lock);
	}

	/**
	 * The checkpoint. Called where continuing would mean touching the user's files
	 * on the strength of a lock that may no longer exist - between batches, and
	 * immediately before an irreversible write.
	 *
	 * <p>
	 * It gates the commit, not the computing. An encode that has finished into the
	 * workspace has cost time and nothing else; what must not happen is that
	 * result being moved into the library by a process no longer entitled to write
	 * there.
	 *
	 * @throws OwnershipLostException when the locks are gone, so that carrying on
	 * is not something a caller can do by forgetting to look
	 */
	public void assertStillOwned() {
		if (!isStillOwned()) {
			throw new OwnershipLostException(
					"Execution " + executionId + " no longer holds the locks it took, and will not go on");
		}
	}

	/**
	 * Gives the locks back. After this the ownership answers no, which is what
	 * keeps a renewal round that overlaps the end of an execution from extending a
	 * lease nobody holds.
	 */
	@Override
	public synchronized void close() {
		if (released) {
			return;
		}

		released = true;

		if (lock != null) {
			lock.close();
		}
	}
}