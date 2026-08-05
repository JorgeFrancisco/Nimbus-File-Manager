package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Renaming from the Files screen, off the queue.
 *
 * <p>
 * No request payload: the two paths are the two path columns, which is the
 * whole of the intent - and they have to be there anyway, because they are what
 * the dispatcher locks before the handler starts. The new name is the file name
 * of the target, so carrying it a second time in a payload would be two places
 * for one truth.
 *
 * <p>
 * Not resumable, like everything that moves the user's files: half of it
 * already happened, and starting again would begin from a world the first
 * attempt changed.
 */
@Component
public class ExplorerRenameJobHandler implements ExecutionJobHandler {

	private final ExplorerRenameService explorerRenameService;

	public ExplorerRenameJobHandler(ExplorerRenameService explorerRenameService) {
		this.explorerRenameService = explorerRenameService;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.EXPLORER_RENAME;
	}

	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		explorerRenameService.execute(execution, ownership);
	}
}