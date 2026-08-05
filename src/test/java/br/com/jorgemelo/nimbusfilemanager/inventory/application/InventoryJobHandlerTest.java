package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryScanRequest;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Turning a claimed row back into a scan.
 *
 * <p>
 * INVENTORY is one of the types that needs no request payload: the folder and
 * whether to recurse are columns of the execution itself, which is what this
 * asserts - a pass rebuilt from the row alone runs over the same tree the user
 * asked for.
 */
class InventoryJobHandlerTest {

	private final InventoryScanRunner inventoryScanRunner = mock(InventoryScanRunner.class);
	private final ScanExclusionService scanExclusionService = mock(ScanExclusionService.class);

	private final InventoryJobHandler handler = new InventoryJobHandler(inventoryScanRunner, scanExclusionService);

	@Test
	void answersForInventoryExecutions() {
		assertThat(handler.type()).isEqualTo(ExecutionType.INVENTORY);
	}

	@Test
	void runsOnlyOneInventoryAtATime() {
		assertThat(handler.concurrencyLimit()).isEqualTo(1);
	}

	/**
	 * Reading the tree and reconciling the catalog leaves nothing behind that a
	 * second pass would double, so one that was abandoned halfway is simply run
	 * again - the answer every writer has to give the other way round.
	 */
	@Test
	void isSimplyRunAgainWhenItWasAbandonedHalfway() {
		assertThat(handler.resumable()).isTrue();
	}

	@Test
	void rebuildsTheScanFromTheRowAlone(@TempDir Path folder) {
		when(scanExclusionService.excludedExtensions()).thenReturn(List.of(".tmp"));
		when(scanExclusionService.excludedFolders()).thenReturn(List.of("cache"));

		Execution execution = execution(true);

		handler.handle(execution, new ClaimedExecution(1L, ExecutionType.INVENTORY.name(), folder.toString(), null,
				null), null);

		ArgumentCaptor<InventoryScanRequest> request = ArgumentCaptor.captor();

		verify(inventoryScanRunner).run(eq(execution), request.capture());

		assertThat(request.getValue().sourcePath()).isEqualTo(folder);
		assertThat(request.getValue().scanOptions().recursive()).isTrue();
		assertThat(request.getValue().scanOptions().excludeExtensions()).containsExactly(".tmp");
		assertThat(request.getValue().scanOptions().excludeFolders()).containsExactly("cache");
	}

	@Test
	void doesNotRecurseWhenTheRowSaysNotTo(@TempDir Path folder) {
		handler.handle(execution(false),
				new ClaimedExecution(1L, ExecutionType.INVENTORY.name(), folder.toString(), null, null), null);

		ArgumentCaptor<InventoryScanRequest> request = ArgumentCaptor.captor();

		verify(inventoryScanRunner).run(any(), request.capture());

		assertThat(request.getValue().scanOptions().recursive()).isFalse();
	}

	private Execution execution(boolean recursive) {
		Execution execution = Execution.builder().executionType(ExecutionType.INVENTORY)
				.status(ExecutionStatus.RUNNING).recursive(recursive).build();

		execution.setId(1L);

		return execution;
	}
}