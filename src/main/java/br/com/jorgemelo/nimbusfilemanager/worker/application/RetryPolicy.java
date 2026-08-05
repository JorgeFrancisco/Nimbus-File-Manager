package br.com.jorgemelo.nimbusfilemanager.worker.application;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;

/**
 * Whether a failure is worth trying again.
 *
 * <p>
 * Everything used to end the same way: the handler threw, the execution became
 * an error, and that was the whole policy. It reads the same in the history
 * whether the file was corrupt or the database happened to be restarting, and
 * only one of those two gets better by waiting.
 *
 * <p>
 * The line is drawn by what threw, not by a string in the message. Spring
 * already classifies its own: {@link TransientDataAccessException} is the
 * family whose contract is that the same operation may succeed on retry, and
 * {@link DataAccessResourceFailureException} is the database not being reachable
 * at all - which is what a worker sees when the application it lives beside is
 * restarting the cluster.
 *
 * <p>
 * Everything else is permanent by default, deliberately. A bad input, a file
 * that will not decode, a path that cannot be written: repeating those spends
 * the attempt budget to arrive at the same answer, and the budget is what keeps
 * a poison job from taking a worker with it.
 */
public final class RetryPolicy {

	/**
	 * A cause chain can be a ring - two exceptions each naming the other is
	 * something the language allows - so the walk is bounded rather than trusted
	 * to end. Far deeper than any real stack of wrappers.
	 */
	private static final int MAX_DEPTH = 32;

	private RetryPolicy() {
	}

	/**
	 * Whether this failure describes the moment rather than the work.
	 *
	 * <p>
	 * The cause chain is walked because the interesting exception is usually
	 * wrapped: a handler catches a data access failure and rethrows it as
	 * something of its own, and what matters is still what was underneath.
	 */
	public static boolean worthRetrying(Throwable failure) {
		Throwable cause = failure;

		for (int depth = 0; cause != null && depth < MAX_DEPTH; depth++) {
			if (cause instanceof TransientDataAccessException || cause instanceof DataAccessResourceFailureException) {
				return true;
			}

			cause = cause.getCause();
		}

		return false;
	}
}