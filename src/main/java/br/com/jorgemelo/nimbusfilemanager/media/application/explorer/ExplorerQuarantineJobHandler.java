package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Sending a selection from the Files screen to quarantine, off the queue.
 *
 * <p>
 * The path columns carry the two ends the operation needs held: the tree being
 * emptied and the quarantine folder it fills. Which files inside that tree are
 * catalogued is read when the work runs rather than when it is asked for -
 * deciding it at enqueue time would be deciding it from a folder that has had
 * time to change.
 */
@Component
public class ExplorerQuarantineJobHandler implements ExecutionJobHandler {

	private final ExplorerDeletionService explorerDeletionService;

	public ExplorerQuarantineJobHandler(ExplorerDeletionService explorerDeletionService) {
		this.explorerDeletionService = explorerDeletionService;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.EXPLORER_QUARANTINE;
	}

	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		explorerDeletionService.quarantine(execution, ownership);
	}
}