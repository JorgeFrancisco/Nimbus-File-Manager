package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCompletionWait;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineMessages;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreOptions;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestorePayload;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestorePlan;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.RestoreOutcome;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Putting one quarantined file back: the question first, then the request.
 *
 * <p>
 * The conversation is held here, while the person is looking - a name collision
 * or a missing origin folder ends the request with an answer and nothing is
 * queued. Once they have decided, what is left is a move like any other, and a
 * move belongs to the worker. This waits a short while for it so the screen
 * answers the way it always did, and hands over the execution when it does not.
 *
 * <p>
 * The two paths of the row name the two ends of the move rather than the
 * quarantine folder, which is what the batch uses: one file going to one place
 * needs those two locked and nothing else, and locking the whole quarantine
 * tree would make every single restore wait for every other.
 */
@Service
public class QuarantineRestoreLauncher extends LocalizedComponent {

	/**
	 * Same budget as every other interactive command: a response, not a timeout.
	 */
	private static final Duration RESPONSE_BUDGET = Duration.ofSeconds(1);

	private final QuarantineRestorePlanner quarantineRestorePlanner;
	private final ExecutionEnqueueService executionEnqueueService;
	private final ExecutionCompletionWait executionCompletionWait;
	private final ExecutionPayloadCodec executionPayloadCodec;
	private final ExecutionMessageCodec executionMessageCodec;
	private final ExecutionMapper executionMapper;

	public QuarantineRestoreLauncher(QuarantineRestorePlanner quarantineRestorePlanner,
			ExecutionEnqueueService executionEnqueueService, ExecutionCompletionWait executionCompletionWait,
			ExecutionPayloadCodec executionPayloadCodec, ExecutionMessageCodec executionMessageCodec,
			ExecutionMapper executionMapper) {
		this.quarantineRestorePlanner = quarantineRestorePlanner;
		this.executionEnqueueService = executionEnqueueService;
		this.executionCompletionWait = executionCompletionWait;
		this.executionPayloadCodec = executionPayloadCodec;
		this.executionMessageCodec = executionMessageCodec;
		this.executionMapper = executionMapper;
	}

	public QuarantineRestoreResult restore(UUID movementId, QuarantineRestoreOptions options) {
		QuarantineRestorePlan plan = quarantineRestorePlanner.plan(movementId, options);

		if (!plan.decided()) {
			return plan.answer();
		}

		Execution queued = executionEnqueueService.enqueueOrExisting(request(movementId, plan));

		return executionCompletionWait.awaitTerminal(queued.getId(), RESPONSE_BUDGET)
				.map(finished -> outcome(movementId, plan.destination(), finished))
				.orElseGet(() -> pending(movementId));
	}

	/**
	 * The deduplication key is the movement: asking twice for the same file to come
	 * back is one request, and the second click is answered with the restore that is
	 * already coming rather than with a second one that would find the quarantine
	 * copy already gone.
	 */
	private Execution request(UUID movementId, QuarantineRestorePlan plan) {
		return Execution.builder().executionType(ExecutionType.QUARANTINE_RESTORE)
				.sourcePath(PathUtils.normalize(plan.quarantined()))
				.targetPath(PathUtils.normalize(plan.destination())).recursive(false).executeFlag(true).filesFound(1)
				.dedupKey(movementId.toString())
				.requestPayload(executionPayloadCodec.encode(new QuarantineRestorePayload(
						QuarantineConstants.PAYLOAD_SCHEMA_VERSION, List.of(movementId),
						PathUtils.normalize(plan.destination()))))
				.statusMessage(started()).build();
	}

	private StatusMessage started() {
		var message = QuarantineMessages.restoreStarted(1);

		return StatusMessage.coded(message.code(), executionMessageCodec.encode(message.args()));
	}

	/**
	 * What the row says, read back as the screen's own vocabulary. Only a file that
	 * actually moved is a restore; anything else is reported with the sentence the
	 * execution recorded, and the person decides what to do next - which is the
	 * same conversation as before, started again, rather than a question asked from
	 * inside the worker.
	 */
	private QuarantineRestoreResult outcome(UUID movementId, Path destination, Execution finished) {
		boolean restored = finished.getFilesMoved() != null && finished.getFilesMoved() == 1
				&& finished.getStatus() == ExecutionStatus.FINISHED;

		if (restored) {
			return new QuarantineRestoreResult(true, RestoreOutcome.RESTORED.name(),
					message("backend.quarantine.restored"), movementId, PathUtils.normalize(destination));
		}

		return new QuarantineRestoreResult(false, RestoreOutcome.ERROR.name(),
				executionMapper.toResponse(finished).message(), movementId, null);
	}

	private QuarantineRestoreResult pending(UUID movementId) {
		return new QuarantineRestoreResult(true, RestoreOutcome.PENDING.name(),
				message("backend.quarantine.restoreProcessing"), movementId, null);
	}
}