package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.util.OptionalLong;

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

	/** The attempt a queued row is on the first time a worker takes it. */
	private static final int FIRST_ATTEMPT = 1;

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
	 * No tree is held, so what keeps this from running beside the work that would
	 * invalidate it is the check below rather than a lock. What keeps it from
	 * writing over a taking that replaced it is the taking itself, carried into
	 * both destructive units - the rebuild and each persisted chunk.
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
			executionProgressService.finishCommand(ownership, ExecutionStatus.REJECTED, new ExecutionCounts(0, 0, 0, 0),
					FingerprintMessages.deferred());

			return;
		}

		if (asksForAFullRebuild(payload, ownership) && !seeded(ownership)) {
			// The taking is over, so nothing was written down and there is nothing left
			// to drain for a row that belongs to somebody else.
			return;
		}

		FingerprintBacklogStatus before = backlog.status();

		executionProgressService.updateTotal(ownership, (int) before.pending());

		DrainResult result = backlog.drainPending(() -> shouldStop(execution.getId()),
				(done, failures) -> executionProgressService.updateLiveProgress(ownership, (int) before.pending(),
						(int) done, 0, (int) failures, FingerprintMessages.started(before.pending())),
				ownership);

		afterDrain(result, ownership);

		finish(execution, ownership, result);
	}

	/**
	 * Whether this run is the request for a full rebuild, rather than a resume of
	 * one.
	 *
	 * <p>
	 * The payload alone cannot say. A rebuild whose worker died is put back on the
	 * queue as the same row, carrying the same payload, so every attempt after the
	 * first would read {@code rebuild = true} and seed the whole library again -
	 * re-owing every file the previous attempts had already done. What tells them
	 * apart is the attempt number, which the queue keeps across a requeue for
	 * exactly this kind of question: the first attempt of a row is the request the
	 * user made, and the ones after it are this same request carrying on.
	 *
	 * <p>
	 * A second click while one is still running is a different row, so it is a
	 * first attempt again, and it tops the list back up to the whole library -
	 * which is what asking twice means.
	 */
	private boolean asksForAFullRebuild(FingerprintBacklogPayload payload, ExecutionOwnership ownership) {
		return payload.rebuildValue() && ownership.claimCount() == FIRST_ATTEMPT;
	}

	/**
	 * Opens the rebuild by writing down what it owes.
	 *
	 * <p>
	 * Nothing is discarded here - not the fingerprints, which stay published until
	 * each is replaced by its own, and not what was derived from them, which is
	 * invalidated file by file as the replacements land. The pin is inside the
	 * seed's transaction, so a run that has been replaced writes down nothing.
	 *
	 * @return whether the work was written down, and so whether there is anything
	 * for this run to go on to
	 */
	private boolean seeded(ExecutionOwnership ownership) {
		OptionalLong owed = backlog.seedRebuild(ownership);

		if (owed.isEmpty()) {
			log.info("Fingerprint rebuild for {}/{} was refused: the execution is no longer the current taking of"
					+ " its row, so nothing was written down", backlog.kind(), backlog.algorithm());

			return false;
		}

		log.info("Fingerprint rebuild for {}/{} owes {} more file(s) after this request", backlog.kind(),
				backlog.algorithm(), owed.getAsLong());

		return true;
	}

	/**
	 * What arrived, once it is written down and not before.
	 *
	 * <p>
	 * Called after the drain rather than after the execution finishes, and
	 * regardless of how the drain ended: a run that was cancelled half way still
	 * wrote the fingerprints it had computed, and those files have arrived as
	 * surely as the ones a complete run wrote.
	 */
	protected void afterDrain(DrainResult result, ExecutionOwnership ownership) {
		// Nothing follows an arrival by default.
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

	private void finish(Execution execution, ExecutionOwnership ownership, DrainResult result) {
		ExecutionStatus status = outcome(execution, result);

		executionProgressService.finishCommand(ownership, status,
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