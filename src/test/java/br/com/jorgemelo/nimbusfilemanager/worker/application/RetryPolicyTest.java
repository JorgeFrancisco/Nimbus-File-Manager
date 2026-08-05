package br.com.jorgemelo.nimbusfilemanager.worker.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The line between a failure of the work and a failure of the moment.
 *
 * <p>
 * Everything used to end as an error, which reads the same in the history
 * whether the file was corrupt or the database was restarting - and only one of
 * those gets better by waiting. Permanent is the default on purpose: repeating a
 * bad input spends the attempt budget to arrive at the same answer, and the
 * budget is what stops a poison job taking a worker with it.
 */
class RetryPolicyTest {

	@Test
	void triesAgainWhenTheDatabaseWasNotReachable() {
		assertThat(RetryPolicy.worthRetrying(new DataAccessResourceFailureException("cluster restarting"))).isTrue();
	}

	@Test
	void triesAgainWhenTheDatabaseAskedForTheOperationToBeRepeated() {
		assertThat(RetryPolicy.worthRetrying(new CannotAcquireLockException("deadlock"))).isTrue();
	}

	/**
	 * The interesting exception is usually wrapped: a handler catches the data
	 * access failure and rethrows something of its own, and what happened is still
	 * what was underneath.
	 */
	@Test
	void looksThroughWhateverWrappedIt() {
		RuntimeException wrapped = new IllegalStateException("the pass could not finish",
				new DataAccessResourceFailureException("cluster restarting"));

		assertThat(RetryPolicy.worthRetrying(wrapped)).isTrue();
	}

	@Test
	void givesUpOnAFailureOfTheWorkItself() {
		assertThat(RetryPolicy.worthRetrying(new IllegalArgumentException("not a video"))).isFalse();
	}

	/** Integrity is the database saying the data is wrong, not that it was busy. */
	@Test
	void givesUpOnAConstraintTheDataWillViolateAgain() {
		assertThat(RetryPolicy.worthRetrying(new DataIntegrityViolationException("duplicate"))).isFalse();
	}

	/** Two exceptions each naming the other is something the language allows. */
	@Test
	void survivesACauseChainThatLoops() {
		RuntimeException first = new IllegalStateException("round");
		RuntimeException second = new IllegalStateException("and round", first);

		first.initCause(second);

		assertThat(RetryPolicy.worthRetrying(first)).isFalse();
	}
}