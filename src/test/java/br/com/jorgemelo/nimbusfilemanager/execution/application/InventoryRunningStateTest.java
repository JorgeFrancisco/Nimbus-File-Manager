package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;

class InventoryRunningStateTest {

	private final ExecutionQueryService executionQueryService = mock(ExecutionQueryService.class);
	private final InventoryRunningState inventoryRunningState = new InventoryRunningState(executionQueryService);

	private static ExecutionResponse execution(String type) {
		return new ExecutionResponse(1L, type, "PROCESSING_FILES", LocalDateTime.now(), null, "src", null, 1, 1, 0, 0,
				0,
				0, null, null, "running", false);
	}

	@Test
	void isRunningIsFalseWhenNoExecutionIsActive() {
		when(executionQueryService.active()).thenReturn(Optional.empty());

		Assertions.assertThat(inventoryRunningState.isRunning()).isFalse();
	}

	@Test
	void isRunningIsFalseWhenTheActiveExecutionIsNotAnInventory() {
		when(executionQueryService.active()).thenReturn(Optional.of(execution("ORGANIZATION")));

		Assertions.assertThat(inventoryRunningState.isRunning()).isFalse();
	}

	@Test
	void isRunningIsTrueWhenAnInventoryExecutionIsActive() {
		when(executionQueryService.active()).thenReturn(Optional.of(execution("INVENTORY")));

		Assertions.assertThat(inventoryRunningState.isRunning()).isTrue();
	}
}