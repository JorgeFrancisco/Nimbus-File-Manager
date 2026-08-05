package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionStatusNames;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

class InventoryRunningStateTest {

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final InventoryRunningState inventoryRunningState = new InventoryRunningState(executionRepository);

	@Test
	void isRunningIsFalseWhenNoInventoryIsActive() {
		when(executionRepository.existsByExecutionTypeAndStatusIn(ExecutionType.INVENTORY,
				ExecutionStatusNames.ACTIVE)).thenReturn(false);

		Assertions.assertThat(inventoryRunningState.isRunning()).isFalse();
	}

	@Test
	void isRunningIsTrueWhenAnInventoryExecutionIsActive() {
		when(executionRepository.existsByExecutionTypeAndStatusIn(ExecutionType.INVENTORY,
				ExecutionStatusNames.ACTIVE)).thenReturn(true);

		Assertions.assertThat(inventoryRunningState.isRunning()).isTrue();
	}

	/**
	 * The question is about inventories, not about statuses in general: an
	 * execution of another type being active is not an inventory being active, and
	 * asking the repository about the type is what keeps the two apart.
	 */
	@Test
	void asksAboutTheInventoryTypeAndNotAboutWhateverIsActive() {
		when(executionRepository.existsByExecutionTypeAndStatusIn(ExecutionType.CONVERSION,
				ExecutionStatusNames.ACTIVE)).thenReturn(true);

		Assertions.assertThat(inventoryRunningState.isRunning()).isFalse();
	}
}