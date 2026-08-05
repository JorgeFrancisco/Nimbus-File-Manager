package br.com.jorgemelo.nimbusfilemanager.worker.application;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;

/**
 * Renewal is one statement for everything this worker holds, from a thread that
 * does no work of its own - a lease that depended on the working thread would
 * lapse every time that thread sat inside ffmpeg.
 *
 * <p>
 * And it renews only what is still owned. Renewing by id alone was the gap: the
 * lease is a row updated through the pool, the locks live in a session of their
 * own, and a session that died went on having its lease extended - the row
 * saying "mine, and healthy" about an execution holding nothing at all. Asking
 * the ownership closes that, and a lease left to lapse is exactly what recovery
 * is for.
 */
class LeaseRenewerTest {

	private final ExecutionQueue executionQueue = mock(ExecutionQueue.class);

	private final LeaseRenewer renewer = new LeaseRenewer(executionQueue,
			new WorkerProperties(null, null, null, null, null, null, null, null, null, null), new WorkerIdentity());

	@Test
	void renewsEveryHeldLeaseInOneCall() {
		renewer.hold(owning(1L));
		renewer.hold(owning(2L));

		when(executionQueue.renewLeases(anyString(), anyList(), anyInt())).thenReturn(2);

		renewer.renew();

		ArgumentCaptor<List<Long>> ids = ArgumentCaptor.captor();

		verify(executionQueue).renewLeases(anyString(), ids.capture(), eq(WorkerProperties.DEFAULT_LEASE_SECONDS));

		Assertions.assertThat(ids.getValue()).containsExactlyInAnyOrder(1L, 2L);
	}

	@Test
	void stopsRenewingWhatItNoLongerHolds() {
		renewer.hold(owning(1L));
		renewer.release(1L);

		renewer.renew();

		verify(executionQueue, never()).renewLeases(anyString(), anyList(), anyInt());
	}

	/**
	 * The case the lease used to hide. Losing the session releases the advisory
	 * locks and tells nobody; renewing anyway would keep the row claiming an
	 * ownership that no longer exists, and nothing else would ever be allowed to
	 * take the work back.
	 */
	@Test
	void stopsRenewingAnExecutionThatLostItsLockSession() {
		renewer.hold(owning(1L));
		renewer.hold(lost(2L));

		when(executionQueue.renewLeases(anyString(), anyList(), anyInt())).thenReturn(1);

		renewer.renew();

		ArgumentCaptor<List<Long>> ids = ArgumentCaptor.captor();

		verify(executionQueue).renewLeases(anyString(), ids.capture(), anyInt());

		Assertions.assertThat(ids.getValue()).containsExactly(1L);
	}

	/** And never asks about it again: it is not this worker's any more. */
	@Test
	void forgetsAnExecutionOnceItsSessionIsGone() {
		renewer.hold(lost(1L));

		renewer.renew();
		renewer.renew();

		verify(executionQueue, never()).renewLeases(anyString(), anyList(), anyInt());
	}

	@Test
	void asksNothingWhenItHoldsNothing() {
		renewer.renew();

		verify(executionQueue, never()).renewLeases(anyString(), anyList(), anyInt());
	}

	/**
	 * Renewing fewer rows than expected means something else now owns the
	 * difference. Recovery has already acted on it, so this is worth saying once
	 * and nothing more - it must not throw, or the renewal thread would die and
	 * take every other lease with it.
	 */
	@Test
	void carriesOnWhenALeaseWasTakenAway() {
		renewer.hold(owning(1L));
		renewer.hold(owning(2L));

		when(executionQueue.renewLeases(anyString(), anyList(), anyInt())).thenReturn(1);

		Assertions.assertThatCode(renewer::renew).doesNotThrowAnyException();
	}

	private ExecutionOwnership owning(long executionId) {
		return ownership(executionId, true);
	}

	private ExecutionOwnership lost(long executionId) {
		return ownership(executionId, false);
	}

	private ExecutionOwnership ownership(long executionId, boolean stillOwned) {
		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		when(ownership.executionId()).thenReturn(executionId);
		when(ownership.isStillOwned()).thenReturn(stillOwned);

		return ownership;
	}
}