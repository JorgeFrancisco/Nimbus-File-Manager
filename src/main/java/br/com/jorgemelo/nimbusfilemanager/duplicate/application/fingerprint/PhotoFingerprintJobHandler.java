package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.SimilarityLauncher;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionTelemetryConsolidation;

/**
 * The photo backlog, off the queue.
 *
 * <p>
 * One at a time, and in a slot of its own: a photo hash is CPU and a decode, a
 * video hash is ffmpeg and several seeks, and the two used to run side by side
 * on a pool of two threads. Separate types keep exactly that - neither waits for
 * the other, and neither runs twice.
 *
 * <p>
 * What is left here is the arrival side: a drain that produced hashes asks for
 * them to be incorporated into the answer on screen. Discarding what was derived
 * from a hash is no longer this class's business - it happens per file, in the
 * transaction that replaces that file's fingerprint.
 */
@Component
public class PhotoFingerprintJobHandler extends FingerprintBacklogJobHandler {

	private final SimilarityLauncher similarityLauncher;

	public PhotoFingerprintJobHandler(PhashBacklogService backlogService,
			SimilarityLauncher similarityLauncher,
			ExecutionProgressService executionProgressService,
			ExecutionCancellationService executionCancellationService,
			ExecutionPayloadCodec executionPayloadCodec,
			ExecutionTelemetryConsolidation telemetryConsolidation) {
		super(backlogService, executionProgressService, executionCancellationService, executionPayloadCodec,
				telemetryConsolidation);
		this.similarityLauncher = similarityLauncher;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.FINGERPRINT_PHOTO;
	}

	/**
	 * Photos that have a fingerprint for the first time are photos the analysis
	 * has never seen, so every answer that exists is now out of date by exactly
	 * them.
	 *
	 * <p>
	 * One request per analysed threshold, deduplicated by family: a backup
	 * dropping three hundred photos drains in batches and asks after each, and the
	 * queue collapses the asking into one execution waiting behind one running.
	 * Nothing is asked for when nothing was written, and nothing at all until
	 * somebody has run an analysis - a library being filled for the first time has
	 * no answer for an arrival to bring up to date.
	 */
	@Override
	protected void afterDrain(DrainResult result, ExecutionOwnership ownership) {
		if (result.processed() > 0 && ownership.takingIsStillCurrent()) {
			similarityLauncher.refreshPhotosAfterArrival();
		}
	}
}