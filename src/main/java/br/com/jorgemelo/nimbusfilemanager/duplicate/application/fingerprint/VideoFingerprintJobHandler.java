package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
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

	public VideoFingerprintJobHandler(VideoFingerprintBacklogService backlogService,
			ExecutionProgressService executionProgressService,
			ExecutionCancellationService executionCancellationService,
			ExecutionPayloadCodec executionPayloadCodec) {
		super(backlogService, executionProgressService, executionCancellationService, executionPayloadCodec);
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.FINGERPRINT_VIDEO;
	}
}