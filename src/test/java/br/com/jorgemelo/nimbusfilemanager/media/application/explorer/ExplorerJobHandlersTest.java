package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * The three commands the Files screen queues, and the handler each one is
 * routed to.
 *
 * <p>
 * What is worth pinning is the routing itself - the dispatcher picks a
 * behaviour by the type on the row, so a handler answering for the wrong type
 * would run a deletion where a rename was asked for - and that none of them
 * declares itself resumable, since every one of them moves or destroys the
 * user's files and a second run would start from a world the first one changed.
 */
class ExplorerJobHandlersTest {

	private final ExplorerRenameService explorerRenameService = mock(ExplorerRenameService.class);
	private final ExplorerDeletionService explorerDeletionService = mock(ExplorerDeletionService.class);
	private final Execution execution = Execution.builder().id(1L).build();
	private final ClaimedExecution claimed = mock(ClaimedExecution.class);
	private final ExecutionOwnership ownership = mock(ExecutionOwnership.class);

	@Test
	void theRenameHandlerAnswersForItsTypeAndRunsTheRename() {
		ExplorerRenameJobHandler handler = new ExplorerRenameJobHandler(explorerRenameService);

		handler.handle(execution, claimed, ownership);

		assertThat(handler.type()).isEqualTo(ExecutionType.EXPLORER_RENAME);
		assertThat(handler.resumable()).isFalse();

		verify(explorerRenameService).execute(execution, ownership);
	}

	@Test
	void theQuarantineHandlerAnswersForItsTypeAndSendsTheSelectionToQuarantine() {
		ExplorerQuarantineJobHandler handler = new ExplorerQuarantineJobHandler(explorerDeletionService);

		handler.handle(execution, claimed, ownership);

		assertThat(handler.type()).isEqualTo(ExecutionType.EXPLORER_QUARANTINE);
		assertThat(handler.resumable()).isFalse();

		verify(explorerDeletionService).quarantine(execution, ownership);
	}

	@Test
	void theDeleteHandlerAnswersForItsTypeAndDeletesForGood() {
		ExplorerDeleteJobHandler handler = new ExplorerDeleteJobHandler(explorerDeletionService);

		handler.handle(execution, claimed, ownership);

		assertThat(handler.type()).isEqualTo(ExecutionType.EXPLORER_DELETE);
		assertThat(handler.resumable()).isFalse();

		verify(explorerDeletionService).deletePermanently(execution, ownership);
	}
}