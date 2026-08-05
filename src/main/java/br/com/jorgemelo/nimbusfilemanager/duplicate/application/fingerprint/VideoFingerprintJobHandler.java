package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.SimilarityLauncher;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;

/**
 * The video backlog, off the queue.
 *
 * <p>
 * Its own type rather than a flag on the photo one, because what bounds it is
 * different: every item spawns ffmpeg, which the {@code ExternalToolGate} and
 * the {@code ProcessingCoordinator} already meter, and a conversion competing
 * for the same binary is why both backlogs step aside for one.
 */
@Component
public class VideoFingerprintJobHandler extends FingerprintBacklogJobHandler {

	private final SimilarityLauncher similarityLauncher;

	public VideoFingerprintJobHandler(VideoFingerprintBacklogService backlogService,
			ExecutionProgressService executionProgressService,
			ExecutionCancellationService executionCancellationService, ExecutionPayloadCodec executionPayloadCodec,
			SimilarityLauncher similarityLauncher) {
		super(backlogService, executionProgressService, executionCancellationService, executionPayloadCodec);
		this.similarityLauncher = similarityLauncher;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.FINGERPRINT_VIDEO;
	}

	/**
	 * Videos that have a fingerprint for the first time are videos the analysis has
	 * never seen, so every answer that exists is now out of date by exactly them.
	 *
	 * <p>
	 * One request per analysed threshold, deduplicated by family: a drone card
	 * dropping two hundred clips drains in batches and asks after each, and the
	 * queue collapses the asking into one execution waiting behind one running.
	 * Nothing is asked for until somebody has run an analysis - a library being
	 * filled for the first time has no answer for an arrival to bring up to date.
	 */
	@Override
	protected void afterDrain(DrainResult result, ExecutionOwnership ownership) {
		if (result.processed() > 0 && ownership.takingIsStillCurrent()) {
			similarityLauncher.refreshVideosAfterArrival();
		}
	}
}