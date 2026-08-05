package br.com.jorgemelo.nimbusfilemanager.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueueSignals;

/**
 * The loop keeps claiming, survives a queue that is failing, and stops when
 * told.
 *
 * <p>
 * Timing is kept out of the assertions: everything here is either "eventually
 * happened" through Mockito's timeout or a flag read directly. The one property
 * that has to hold is that a worker asked to stop stops - a loop that ignored
 * that would keep claiming work while the process is trying to shut down.
 */
class WorkerLoopTest {

	private static final int FAST_POLL_SECONDS = 1;

	private final ExecutionDispatcher executionDispatcher = mock(ExecutionDispatcher.class);
	private final LeaseRenewer leaseRenewer = mock(LeaseRenewer.class);
	private final ExecutionQueueSignals executionQueueSignals = mock(ExecutionQueueSignals.class);

	private final WorkerLoop loop = new WorkerLoop(executionDispatcher, leaseRenewer, executionQueueSignals,
			new WorkerProperties(1, null, FAST_POLL_SECONDS, FAST_POLL_SECONDS, null, null, null, null, null, null));

	@AfterEach
	void stop() {
		loop.shutdown();
	}

	@Test
	void keepsAskingTheQueueForWork() {
		when(executionDispatcher.dispatchOne()).thenReturn(true);

		loop.start();

		verify(executionDispatcher, timeout(5000).atLeast(2)).dispatchOne();
	}

	@Test
	void renewsLeasesOnItsOwnSchedule() {
		when(executionDispatcher.dispatchOne()).thenReturn(false);

		loop.start();

		verify(leaseRenewer, timeout(5000).atLeastOnce()).renew();
	}

	/**
	 * A queue that throws must not kill the loop: the database may be briefly
	 * unreachable, and a worker that gave up on the first failure would stay dead
	 * until someone restarted it.
	 */
	@Test
	void carriesOnAfterTheQueueFails() {
		when(executionDispatcher.dispatchOne()).thenThrow(new IllegalStateException("gone"));

		loop.start();

		verify(executionDispatcher, timeout(5000).atLeast(2)).dispatchOne();
	}

	@Test
	void carriesOnAfterARenewalRoundFails() {
		when(executionDispatcher.dispatchOne()).thenReturn(false);

		doThrow(new IllegalStateException("gone")).when(leaseRenewer).renew();

		loop.start();

		verify(leaseRenewer, timeout(5000).atLeastOnce()).renew();

		assertThat(loop.isAccepting()).isTrue();
	}

	@Test
	void stopsAcceptingWhenAsked() {
		when(executionDispatcher.dispatchOne()).thenReturn(false);

		loop.start();

		loop.stopAccepting();

		assertThat(loop.isAccepting()).isFalse();
	}

	@Test
	void stopsEverythingOnShutdown() {
		when(executionDispatcher.dispatchOne()).thenReturn(false);

		loop.start();

		verify(executionDispatcher, timeout(5000).atLeastOnce()).dispatchOne();

		loop.shutdown();

		assertThat(loop.isAccepting()).isFalse();
	}
}