package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Deleting for good from the Files screen, off the queue.
 *
 * <p>
 * The most irreversible thing this product does, and until now the only one
 * that left no trace of itself. It has a row like everything else now: what was
 * asked, when, by which process, how many files went and what the outcome was -
 * which is the difference between "my photos are gone" and an answer.
 *
 * <p>
 * The row is history, not a handle. Having one does not make the deletion
 * something that can be called back: see {@link ExplorerDeletionService} for
 * where the point of no return is and why nothing here honours a cancellation.
 */
@Component
public class ExplorerDeleteJobHandler implements ExecutionJobHandler {

	private final ExplorerDeletionService explorerDeletionService;

	public ExplorerDeleteJobHandler(ExplorerDeletionService explorerDeletionService) {
		this.explorerDeletionService = explorerDeletionService;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.EXPLORER_DELETE;
	}

	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		explorerDeletionService.deletePermanently(execution, ownership);
	}
}