package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.nio.file.Path;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationPathKey;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;

/**
 * Asking for the folder the Files screen just listed to be reconciled.
 *
 * <p>
 * This is what is left of the reconciliation the screen used to perform itself.
 * Listing a folder is the one moment this product looks at a place the watcher
 * never does - the explorer browses anywhere on disk, while the inventory and
 * the scheduled pass only ever see the monitored library - so what it notices
 * there is worth acting on. What it must not do is act on it: marking catalog
 * entries missing while rendering a page was a second reconciliation engine,
 * with its own idea of what missing means, running in the process that answers
 * requests.
 *
 * <p>
 * So the screen reports and the worker repairs, through the one pass that
 * already exists. Shallow, because a listing is shallow: the request says
 * exactly the folder that was looked at, and the reconcile compares the same
 * universe on both sides.
 *
 * <p>
 * Nothing is asked for when nothing was missing, which is what keeps this from
 * becoming a request per page view. When something was, the deduplication key -
 * the folder - means the refresh a few seconds later finds the work already
 * queued rather than queueing it again.
 */
@Service
public class ExplorerReconcileLauncher {

	private final ExecutionEnqueueService executionEnqueueService;

	public ExplorerReconcileLauncher(ExecutionEnqueueService executionEnqueueService) {
		this.executionEnqueueService = executionEnqueueService;
	}

	/**
	 * Runs in its own transaction because browsing is a read-only one, and a
	 * read-only transaction cannot write the row this queues.
	 *
	 * <p>
	 * A repair already waiting answers this listing; one already <em>running</em>
	 * does not. A reconcile compares the folder once, and this is only ever called
	 * because the screen is displaying an entry the disk no longer has - so a
	 * request arriving now is about a state the running pass may well have gone
	 * past. Refusing it would leave the screen showing a problem that nothing was
	 * asked to fix.
	 *
	 * <p>
	 * The second listing used to break the page instead: the deduplication index
	 * refused the duplicate, that marked this transaction rollback-only, and the
	 * {@code UnexpectedRollbackException} surfaced on its commit. That is now
	 * settled by asking before inserting and by the advisory lock behind it, rather
	 * than by dropping the request.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void repairFolder(Path folder) {
		executionEnqueueService.enqueue(Execution.builder().executionType(ExecutionType.RECONCILE)
				.triggerEvent(ExecutionTrigger.FILE_EVENT).sourcePath(folder.toString()).recursive(false)
				.executeFlag(true).dedupKey(OperationPathKey.canonical(folder))
				.statusMessage(StatusMessage.code(ExecutionMessages.RECONCILE_REPAIRED)).build());
	}
}