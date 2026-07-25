package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;


class StartupExecutionRecoveryListenerTest {

	@Test
	void onApplicationEventShouldMarkInterruptedExecutions() {
		ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);

		ApplicationReadyEvent event = mock(ApplicationReadyEvent.class);

		new StartupExecutionRecoveryListener(executionProgressService).onApplicationEvent(event);

		verify(executionProgressService).markInterruptedExecutions();
	}
}