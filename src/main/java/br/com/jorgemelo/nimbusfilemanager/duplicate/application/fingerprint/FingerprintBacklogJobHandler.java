package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintMessages;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogPayload;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import lombok.extern.slf4j.Slf4j;

/**
 * Drains a fingerprint backlog that came off the queue.
 *
 * <p>
 * One class for both media, holding the backlog its subclass names. What differs
 * between photos and videos is the cost of an item and the tools it needs, not
 * the shape of the work: read what is missing, compute, write, report.
 *
 * <p>
 * Resumable, and not by checkpoint. A backlog is a query - the files of this
 * kind that have no fingerprint - so a second attempt simply asks again and
 * finds what the first did not reach. There is no position to remember because
 * the work describes itself.
 */
@Slf4j
abstract class FingerprintBacklogJobHandler implements ExecutionJobHandler {

	private final FingerprintBacklog backlog;
	private final ExecutionProgressService executionProgressService;
	private final ExecutionCancellationService executionCancellationService;
	private final ExecutionPayloadCodec executionPayloadCodec;

	protected FingerprintBacklogJobHandler(FingerprintBacklog backlog,
			ExecutionProgressService executionProgressService,
			ExecutionCancellationService executionCancellationService,
			ExecutionPayloadCodec executionPayloadCodec) {
		this.backlog = backlog;
		this.executionProgressService = executionProgressService;
		this.executionCancellationService = executionCancellationService;
		this.executionPayloadCodec = executionPayloadCodec;
	}

	/**
	 * A backlog is a query - the files of this kind that have no fingerprint -
	 * so there is no folder to hold. It reads media and writes fingerprint rows;
	 * it moves nothing.
	 */
	@Override
	public boolean requiresPathLock() {
		return false;
	}

	@Override
	public boolean resumable() {
		return true;
	}

	/**
	 * The ownership goes unused: this writes fingerprints of files it does not
	 * touch. What keeps it from running beside the work that would invalidate it is
	 * the check below, not a lock over a tree.
	 */
	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		FingerprintBacklogPayload payload = payloadOf(claimed);

		// An inventory is adding the very files this would hash, and a conversion is
		// using the ffmpeg this needs. Stepping aside is the behaviour that already
		// existed; what changed is that stepping aside now ends a row instead of
		// leaving a thread to try again, and the inventory asks for a new one when it
		// finishes.
		if (backlog.pausedByActiveExecution()) {
			executionProgressService.finishCommand(execution, ExecutionStatus.REJECTED, new ExecutionCounts(0, 0, 0, 0),
					FingerprintMessages.deferred());

			return;
		}

		if (payload.rebuildValue()) {
			long cleared = backlog.rebuild();

			log.info("Fingerprint rebuild for {}/{} discarded {} fingerprint(s)", backlog.kind(), backlog.algorithm(),
					cleared);
		}

		FingerprintBacklogStatus before = backlog.status();

		executionProgressService.updateTotal(execution, (int) before.pending());

		DrainResult result = backlog.drainPending(() -> shouldStop(execution.getId()),
				(done, failures) -> executionProgressService.updateLiveProgress(execution, (int) before.pending(),
						(int) done, 0, (int) failures, FingerprintMessages.started(before.pending())));

		finish(execution, result);
	}

	/**
	 * Two reasons to stop, and both are read from the database rather than from a
	 * field: the user cancelled, or something started that this must not compete
	 * with. A drain that stops halfway leaves the fingerprints it already wrote,
	 * and the next run continues from what is still missing.
	 */
	private boolean shouldStop(Long executionId) {
		return executionCancellationService.isCancelled(executionId) || backlog.pausedByActiveExecution();
	}

	private void finish(Execution execution, DrainResult result) {
		ExecutionStatus status = outcome(execution, result);

		executionProgressService.finishCommand(execution, status,
				new ExecutionCounts((int) (result.processed() + result.failed()), (int) result.processed(), 0,
						(int) result.failed()),
				FingerprintMessages.completed(result.processed(), result.failed()));
	}

	/**
	 * Stopping wins over failing: a drain that was told to stand down did not fail,
	 * it was interrupted, and whatever it had already written stays written.
	 */
	private ExecutionStatus outcome(Execution execution, DrainResult result) {
		if (shouldStop(execution.getId())) {
			return ExecutionStatus.CANCELLED;
		}

		return result.failed() > 0 ? ExecutionStatus.FINISHED_WITH_ERRORS : ExecutionStatus.FINISHED;
	}

	/**
	 * A payload written in a shape this version does not know is refused rather
	 * than read as far as it goes: reading a rebuild flag wrong would discard every
	 * fingerprint in the catalog.
	 */
	private FingerprintBacklogPayload payloadOf(ClaimedExecution claimed) {
		FingerprintBacklogPayload payload = executionPayloadCodec.decode(claimed.requestPayload(),
				FingerprintBacklogPayload.class);

		if (payload.schemaVersion() == null
				|| payload.schemaVersion() != DuplicateConstants.FINGERPRINT_PAYLOAD_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Fingerprint backlog payload schema " + payload.schemaVersion()
					+ " cannot be run by this version, which writes and reads schema "
					+ DuplicateConstants.FINGERPRINT_PAYLOAD_SCHEMA_VERSION);
		}

		return payload;
	}
}