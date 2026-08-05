package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import br.com.jorgemelo.nimbusfilemanager.worker.application.ExecutionReclaim;

/**
 * The application's start applies the recovery policy rather than one of its
 * own. It used to have one - mark everything interrupted, queue no reconcile -
 * which meant that whether an abandoned organization got its divergence repaired
 * depended on which process happened to start first.
 */
class StartupExecutionRecoveryListenerTest {

	@Test
	void onApplicationEventShouldReclaimAbandonedExecutions() {
		ExecutionReclaim executionReclaim = mock(ExecutionReclaim.class);

		ApplicationReadyEvent event = mock(ApplicationReadyEvent.class);

		new StartupExecutionRecoveryListener(executionReclaim).onApplicationEvent(event);

		verify(executionReclaim).reclaimAbandoned();
	}
}