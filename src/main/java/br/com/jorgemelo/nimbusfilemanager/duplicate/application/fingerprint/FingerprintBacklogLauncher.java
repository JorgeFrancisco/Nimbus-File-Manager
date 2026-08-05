package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.util.Optional;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogPayload;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import lombok.extern.slf4j.Slf4j;

/**
 * Asking for a fingerprint backlog to be drained.
 *
 * <p>
 * There is nothing to hand over and nothing to remember. A backlog is defined by
 * a query - the files of this kind that have no fingerprint yet - so a request
 * carries no work item and a run computes what is missing at the moment it runs.
 * That is why this is a launcher and not a scheduler: it says "there is work",
 * once, and the queue decides when.
 */
@Slf4j
@Service
public class FingerprintBacklogLauncher {

	private final PhashBacklogService photoBacklog;
	private final VideoFingerprintBacklogService videoBacklog;
	private final ExecutionEnqueueService executionEnqueueService;
	private final ExecutionPayloadCodec executionPayloadCodec;
	private final BackgroundWorkGate backgroundWorkGate;

	public FingerprintBacklogLauncher(PhashBacklogService photoBacklog, VideoFingerprintBacklogService videoBacklog,
			ExecutionEnqueueService executionEnqueueService, ExecutionPayloadCodec executionPayloadCodec,
			BackgroundWorkGate backgroundWorkGate) {
		this.photoBacklog = photoBacklog;
		this.videoBacklog = videoBacklog;
		this.executionEnqueueService = executionEnqueueService;
		this.executionPayloadCodec = executionPayloadCodec;
		this.backgroundWorkGate = backgroundWorkGate;
	}

	/**
	 * Both backlogs: a conversion competes for ffmpeg with photos and videos alike.
	 */
	public void launchBoth() {
		launch(ExecutionType.FINGERPRINT_PHOTO, false);
		launch(ExecutionType.FINGERPRINT_VIDEO, false);
	}

	/**
	 * Asks for a drain, or joins the one already waiting.
	 *
	 * @param rebuild whether the fingerprints of this kind are to be discarded
	 * first. It is part of the identity of the request, not a detail of it: "redo
	 * everything" and "finish what is missing" are different things to ask for, and
	 * collapsing one onto the other would answer a question nobody asked
	 */
	public Optional<Execution> launch(ExecutionType type, boolean rebuild) {
		// Queueing while the application is closing writes a row that nothing will
		// claim before the pool goes. Nothing is lost: the backlog is whatever is
		// still pending, and the next start finds it.
		if (backgroundWorkGate.standDown()) {
			return Optional.empty();
		}

		// Nothing pending is nothing to ask for. A row queued over an empty backlog
		// would be claimed, find no work and finish - a run in the history that never
		// did anything, once per restart and once per inventory. A rebuild is exempt
		// because it makes the work it is asking about.
		if (!rebuild && backlogOf(type).status().pending() == 0) {
			return Optional.empty();
		}

		Execution queued = Execution.builder().executionType(type).recursive(false).executeFlag(true)
				.dedupKey(dedupKey(type, rebuild))
				.requestPayload(executionPayloadCodec.encode(new FingerprintBacklogPayload(
						DuplicateConstants.FINGERPRINT_PAYLOAD_SCHEMA_VERSION, rebuild)))
				.statusMessage(StatusMessage.code(DuplicateConstants.FINGERPRINT_STARTED)).build();

		return Optional.of(executionEnqueueService.enqueueOrExisting(queued));
	}

	/**
	 * Two requests to drain the same backlog are the same request.
	 *
	 * <p>
	 * Unlike an organization - where two runs over the same folders are two things
	 * a person asked for - a backlog has no arguments: it is "everything of this
	 * kind that is missing one". A second drain queued beside the first would
	 * compute nothing the first did not already reach. A rebuild is not the same
	 * request, which is why it is in the key.
	 */
	private FingerprintBacklog backlogOf(ExecutionType type) {
		return type == ExecutionType.FINGERPRINT_VIDEO ? videoBacklog : photoBacklog;
	}

	private String dedupKey(ExecutionType type, boolean rebuild) {
		return type.name() + (rebuild ? ":rebuild" : ":drain");
	}
}