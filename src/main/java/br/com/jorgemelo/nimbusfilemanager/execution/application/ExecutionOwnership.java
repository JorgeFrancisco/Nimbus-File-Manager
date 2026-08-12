package br.com.jorgemelo.nimbusfilemanager.execution.application;

/**
 * One taking of one execution: which attempt of the row this is, and the claim
 * on the paths it works over.
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
 * The two properties it holds are asked for separately, and that separation is
 * the point rather than a convenience. <strong>Being the current taking</strong>
 * is what entitles a caller to write about the row; <strong>holding the path
 * locks</strong> is what entitles it to touch the user's files. They fail
 * independently, and one method answering both meant every caller got the
 * stricter answer whether or not it needed it - which is how an execution that
 * lost its lock session ended up unable to record that it had stopped.
 *
 * <p>
 * Two threads reach the lock half: the one doing the work, at its checkpoints,
 * and the renewer, once a round. They would otherwise use the lock connection at
 * the same time, and a JDBC connection is not shared - so both go through this,
 * one at a time. The cost is nothing: the connection is idle between acquiring
 * and releasing, and this is all anyone asks it.
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
	private final ExecutionOwnershipGuard executionOwnershipGuard;

	/**
	 * Null until the attempt has been counted, and fixed from then on. It is the
	 * half of the identity that separates this taking of the row from a later one
	 * by the same worker, so it is assigned once and never again - an object that
	 * could change it would be an object that changes who it is.
	 */
	private Integer claimCount;

	private boolean released;

	ExecutionOwnership(long executionId, OperationLock lock, OperationLockService operationLockService,
			ExecutionOwnershipGuard executionOwnershipGuard) {
		this.executionId = executionId;
		this.lock = lock;
		this.operationLockService = operationLockService;
		this.executionOwnershipGuard = executionOwnershipGuard;
	}

	/**
	 * Names the taking, once the attempt has been counted and the number exists.
	 *
	 * @throws IllegalStateException on a second call - reassigning would swap this
	 * object's identity while callers are already holding it as the answer to
	 * "which taking am I?"
	 */
	public synchronized void attemptStarted(int startedClaimCount) {
		if (claimCount != null) {
			throw new IllegalStateException("Execution " + executionId + " is already the taking at attempt "
					+ claimCount + " and cannot become attempt " + startedClaimCount);
		}

		claimCount = startedClaimCount;
	}

	/**
	 * @throws IllegalStateException when asked before the attempt was counted -
	 * nothing may write about a taking that has not begun
	 */
	public synchronized int claimCount() {
		if (claimCount == null) {
			throw new IllegalStateException("Execution " + executionId + " has no attempt counted yet");
		}

		return claimCount;
	}

	/**
	 * Holds this taking in force for the rest of the caller's transaction, so the
	 * domain write about to happen cannot be overtaken before it commits. The
	 * number sent is this object's own, never the one the row currently carries.
	 */
	public boolean pin() {
		return executionOwnershipGuard.pin(executionId, claimCount());
	}

	/**
	 * Holds this taking in force for a write that happens <em>after</em> the run
	 * ended - the telemetry consolidation, and nothing else so far.
	 *
	 * <p>
	 * Separate from {@link #pin()} because the two are asking different things.
	 * That one guards work in progress, so it demands the row still be RUNNING
	 * under a live lease; by the time the measurements are written the outcome is
	 * committed and every one of those conditions is false. What still has to hold
	 * is that no later attempt has taken the row: being finished does not make an
	 * attempt stale, being replaced does.
	 */
	public boolean pinAttempt() {
		return executionOwnershipGuard.pinAttempt(executionId, claimCount());
	}

	public long executionId() {
		return executionId;
	}

	/**
	 * Whether this object is still the taking the row belongs to - the question
	 * every write <em>about the execution</em> has to ask, and the only one it has
	 * to ask.
	 *
	 * <p>
	 * Deliberately says nothing about the path locks. Losing them means this
	 * process may no longer touch the user's files; it does not mean the row
	 * stopped being this taking's to report on. Conflating the two is what left an
	 * execution whose lock session died unable to write its own INTERRUPTED - the
	 * one sentence it still had every right, and every reason, to record.
	 *
	 * <p>
	 * A taking whose attempt was never counted has no number for a later one to
	 * differ from, so there is nothing it could have been replaced by and the
	 * answer is yes. A released one answers no: everything it held is back.
	 *
	 * <p>
	 * <strong>Cooperative, and answered from memory.</strong> It exists so that a
	 * run which has already been replaced stops starting new work, and a yes from
	 * it is worth nothing by the time the next statement executes. It does not
	 * authorise a persistent write and it is not a substitute for {@link #pin()},
	 * which is the only thing that makes a domain mutation refusable - see
	 * {@code ExecutionQueue#pin}.
	 */
	public synchronized boolean takingIsStillCurrent() {
		if (released) {
			return false;
		}

		return claimCount == null || executionOwnershipGuard.isTheCurrentTaking(executionId, claimCount);
	}

	/**
	 * Whether the session that took the path locks is still holding them.
	 *
	 * <p>
	 * Work whose type holds no tree took no lock and so has none to lose, and says
	 * so. That answer is safe only because nothing asks this question on its own:
	 * {@link #mayGoOnWorking()} asks it beside the taking, so a type with no path
	 * lock is still held to being the current taking rather than waved through -
	 * which is what "no lock, so yes" used to do to exactly the types that have no
	 * other exclusion.
	 */
	synchronized boolean stillHoldsOperationLock() {
		return lock == null || operationLockService.stillHolds(lock);
	}

	/**
	 * Both properties, which is what carrying on with the <em>work</em> requires:
	 * the row is still this taking's, and the tree under it is still held.
	 */
	public synchronized boolean mayGoOnWorking() {
		return takingIsStillCurrent() && stillHoldsOperationLock();
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
	 * @throws OwnershipLostException when either property is gone, so that
	 * carrying on is not something a caller can do by forgetting to look
	 */
	public void assertMayGoOnWorking() {
		if (!mayGoOnWorking()) {
			throw new OwnershipLostException(
					"Execution " + executionId + " no longer holds the locks it took, and will not go on");
		}
	}

	/**
	 * Gives the locks back and ends the taking. After this
	 * {@link #takingIsStillCurrent()} answers no, which is what keeps a renewal
	 * round that overlaps the end of an execution from extending a lease nobody
	 * holds - and what stops a late write from a run that is entirely over.
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