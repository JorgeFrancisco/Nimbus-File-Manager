package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;

/**
 * An {@link ExecutionCancellationService} whose row lives in a set instead of
 * PostgreSQL, for the many unit tests that cancel their own work mid-loop and
 * have nothing to say about where the flag is stored.
 *
 * <p>
 * It remembers, which is the point: a request made through the service has to
 * be visible to the next check, exactly as the column would be. Faking that
 * keeps those tests from needing a database to prove something else entirely.
 */
public final class NoCancellations {

	private NoCancellations() {
	}

	public static ExecutionCancellationService none() {
		Set<Long> cancelled = ConcurrentHashMap.newKeySet();

		ExecutionQueue queue = mock(ExecutionQueue.class);

		lenient().when(queue.requestCancel(anyLong())).thenAnswer(invocation -> cancelled.add(invocation
				.getArgument(0, Long.class)));
		lenient().when(queue.isCancelRequested(anyLong()))
				.thenAnswer(invocation -> cancelled.contains(invocation.getArgument(0, Long.class)));

		return new ExecutionCancellationService(queue, Clock.systemUTC());
	}
}