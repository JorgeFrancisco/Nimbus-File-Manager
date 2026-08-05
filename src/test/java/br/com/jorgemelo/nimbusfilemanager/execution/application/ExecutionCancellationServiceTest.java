package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;

/**
 * Cancellation, now that the request outlives the process that made it.
 *
 * <p>
 * The flag used to be an {@code AtomicBoolean} in a map, so a cancel could only
 * reach work running in the same JVM. These tests are about the two things that
 * changed: the request goes to the row, and the answer is read back through a
 * cache short enough that nobody notices it, yet real enough that a per-file
 * check does not become a per-file query.
 */
class ExecutionCancellationServiceTest {

	private final ExecutionQueue executionQueue = mock(ExecutionQueue.class);

	private final AdvanceableClock clock = new AdvanceableClock(Instant.parse("2026-08-04T10:00:00Z"));

	private final ExecutionCancellationService service = new ExecutionCancellationService(executionQueue, clock);

	@Test
	void reportsNoCancellationWithoutAnExecution() {
		assertThat(service.isCancelled(null)).isFalse();
	}

	@Test
	void asksTheRowWhetherCancellationWasRequested() {
		when(executionQueue.isCancelRequested(7L)).thenReturn(true);

		assertThat(service.isCancelled(7L)).isTrue();
	}

	@Test
	void recordsTheRequestOnTheRowSoAnotherProcessCanSeeIt() {
		when(executionQueue.requestCancel(7L)).thenReturn(true);

		assertThat(service.requestCancellation(7L)).isTrue();

		verify(executionQueue).requestCancel(7L);
	}

	@Test
	void reportsFailureWhenThereIsNothingRunningToCancel() {
		when(executionQueue.requestCancel(7L)).thenReturn(false);

		assertThat(service.requestCancellation(7L)).isFalse();
	}

	@Test
	void ignoresARequestWithoutAnExecution() {
		assertThat(service.requestCancellation(null)).isFalse();

		verify(executionQueue, never()).requestCancel(anyLong());
	}

	/**
	 * The check sits inside per-file loops, so repeating it must not repeat the
	 * query.
	 */
	@Test
	void answersRepeatedChecksWithoutAskingAgain() {
		when(executionQueue.isCancelRequested(7L)).thenReturn(false);

		service.isCancelled(7L);
		service.isCancelled(7L);
		service.isCancelled(7L);

		verify(executionQueue, times(1)).isCancelRequested(7L);
	}

	@Test
	void asksAgainOnceTheAnswerHasGoneStale() {
		when(executionQueue.isCancelRequested(7L)).thenReturn(false, true);

		assertThat(service.isCancelled(7L)).isFalse();

		clock.advance(Duration.ofSeconds(2));

		assertThat(service.isCancelled(7L)).isTrue();
	}

	/**
	 * A cancel arriving in this process must not wait out the cache: the runner
	 * may well be here, and half a second of "not cancelled" is half a second of
	 * files still moving.
	 */
	@Test
	void dropsAStaleAnswerAsSoonAsCancellationIsRequestedHere() {
		when(executionQueue.isCancelRequested(7L)).thenReturn(false, true);
		when(executionQueue.requestCancel(7L)).thenReturn(true);

		assertThat(service.isCancelled(7L)).isFalse();

		service.requestCancellation(7L);

		assertThat(service.isCancelled(7L)).isTrue();
	}

	/**
	 * Dropping a cached answer is housekeeping, so being handed nothing is not an
	 * error - it is a call with nothing to drop.
	 */
	@Test
	void forgettingDropsTheCachedAnswerWithoutChangingIt() {
		when(executionQueue.isCancelRequested(7L)).thenReturn(true);

		assertThat(service.isCancelled(7L)).isTrue();

		service.forget(null);
		service.forget(7L);

		// The row still says cancelled: what was dropped is the memory of having
		// asked, never the answer.
		assertThat(service.isCancelled(7L)).isTrue();
	}

	/**
	 * Everywhere, not here. Walking the executions this process started was true
	 * while there was only one process: with the work in another JVM it meant an
	 * administrative operation asking the database whether anything was still
	 * active, seeing the worker's inventory, and cancelling nothing - waiting for
	 * something it had never asked to stop.
	 */
	@Test
	void cancelsEveryExecutionAnywhereRatherThanTheOnesItStarted() {
		when(executionQueue.requestCancelOfEverything()).thenReturn(3);

		assertThat(service.requestAllCancellations()).isEqualTo(3);

		verify(executionQueue).requestCancelOfEverything();
		verify(executionQueue, never()).requestCancel(anyLong());
	}

	/**
	 * The cached answers are dropped with the request, so a loop that asked half a
	 * second ago is told the truth on its next check rather than after the cache
	 * decides to expire.
	 */
	@Test
	void forgetsWhatItLastAnsweredWhenEverythingIsCancelled() {
		when(executionQueue.isCancelRequested(7L)).thenReturn(false, true);

		assertThat(service.isCancelled(7L)).isFalse();

		service.requestAllCancellations();

		assertThat(service.isCancelled(7L)).isTrue();
	}
}