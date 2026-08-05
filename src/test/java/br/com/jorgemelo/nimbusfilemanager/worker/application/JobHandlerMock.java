package br.com.jorgemelo.nimbusfilemanager.worker.application;

import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;

/**
 * A handler mock that starts where a real handler starts.
 *
 * <p>
 * A plain mock answers zero, false and null to everything it was not told
 * about, and that is not what an implementation answers: the interface's
 * defaults are the policy - one at a time, holds the path locks, not resumable
 * - and a mock quietly replaces all three with the opposite of two of them.
 * That is not a hypothetical. Adding {@code requiresPathLock()} broke eight
 * dispatcher tests at once, because every mock in them had silently become a
 * handler that runs outside the exclusion, which no handler in the product is
 * by default.
 *
 * <p>
 * So the mock is told to run the interface's own methods, and the tests state
 * only what they mean to change. The property that buys is that the next
 * default method added to {@code ExecutionJobHandler} arrives in these tests
 * already answering what production answers, instead of arriving as a silent
 * false.
 */
final class JobHandlerMock {

	private JobHandlerMock() {
	}

	static ExecutionJobHandler answeringItsOwnDefaults() {
		return mock(ExecutionJobHandler.class, CALLS_REAL_METHODS);
	}
}