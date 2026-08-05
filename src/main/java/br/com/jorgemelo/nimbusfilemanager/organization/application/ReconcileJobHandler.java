package br.com.jorgemelo.nimbusfilemanager.organization.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Runs a reconcile that came off the queue.
 *
 * <p>
 * Like the inventory, it needs no request payload: the folder is the row's
 * {@code sourcePath}. And like the inventory, it is idempotent - comparing disk
 * against catalog and repairing the difference gives the same answer however
 * many times it runs, which is what makes retrying after a crash the whole of
 * the recovery.
 *
 * <p>
 * The dispatcher already holds the path locks by the time this is called, and
 * {@code reconcileAndApply} takes its own for the apply. Acquiring the same
 * paths again on the same thread reenters rather than deadlocks, which is the
 * property the lock session was built to keep.
 */
@Component
public class ReconcileJobHandler implements ExecutionJobHandler {

	private final OrganizationReconcileApply organizationReconcileApply;
	private final ExecutionProgressService executionProgressService;

	public ReconcileJobHandler(OrganizationReconcileApply organizationReconcileApply,
			ExecutionProgressService executionProgressService) {
		this.organizationReconcileApply = organizationReconcileApply;
		this.executionProgressService = executionProgressService;
	}

	/**
	 * Reading the tree and reconciling the catalog leaves nothing behind that a
	 * second pass would double, so an abandoned one is simply run again.
	 */
	@Override
	public boolean resumable() {
		return true;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.RECONCILE;
	}

	/**
	 * No checkpoint between items: this handler reads the tree and writes the
	 * catalog, and losing the locks halfway through leaves nothing on disk to be
	 * wrong - a checkpoint belongs where a mutation would be committed. The
	 * ownership is still what closes the row, because that write is about a taking
	 * like every other.
	 */
	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		OrganizationReconcileResponse response = organizationReconcileApply.reconcileAndApply(
				new OrganizationReconcileRequest(claimed.sourcePath(), execution.getRecursive(), false, null));

		// filesFound is what the walk found on disk, which is what the name says.
		// repairedItems is what this pass actually changed, counted once per entry -
		// and it is what tells an automatic pass that did something apart from one
		// that found nothing to do.
		executionProgressService.finishReconcile(ownership, (int) response.filesOnDisk(),
				(int) response.repairedItems(), ExecutionMessages.reconcileRepaired(response.renamed(),
						response.repairedPaths(), response.markedMissing()));
	}
}