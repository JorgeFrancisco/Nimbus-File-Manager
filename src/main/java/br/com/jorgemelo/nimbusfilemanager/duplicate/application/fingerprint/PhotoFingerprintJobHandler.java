package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;

/**
 * The photo backlog, off the queue.
 *
 * <p>
 * One at a time, and in a slot of its own: a photo hash is CPU and a decode, a
 * video hash is ffmpeg and several seeks, and the two used to run side by side
 * on a pool of two threads. Separate types keep exactly that - neither waits for
 * the other, and neither runs twice.
 */
@Component
public class PhotoFingerprintJobHandler extends FingerprintBacklogJobHandler {

	public PhotoFingerprintJobHandler(PhashBacklogService backlogService,
			ExecutionProgressService executionProgressService,
			ExecutionCancellationService executionCancellationService,
			ExecutionPayloadCodec executionPayloadCodec) {
		super(backlogService, executionProgressService, executionCancellationService, executionPayloadCodec);
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.FINGERPRINT_PHOTO;
	}
}