package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityMessages;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisPayload;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityComposition;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFamily;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;

/**
 * Asking for a similarity analysis.
 *
 * <p>
 * The screen never waits for one. Grouping a library takes minutes, the answer
 * is durable, and the previous one stays readable meanwhile - so there is
 * nothing a bounded wait would buy here, unlike the single-file commands of the
 * Files screen. What comes back is the execution to follow.
 */
@Service
public class SimilarityLauncher {

	private final PhotoSimilarityService photoSimilarityService;
	private final VideoSimilarityService videoSimilarityService;
	private final ExecutionEnqueueService executionEnqueueService;
	private final ExecutionPayloadCodec executionPayloadCodec;
	private final ExecutionMessageCodec executionMessageCodec;

	public SimilarityLauncher(PhotoSimilarityService photoSimilarityService,
			VideoSimilarityService videoSimilarityService, ExecutionEnqueueService executionEnqueueService,
			ExecutionPayloadCodec executionPayloadCodec, ExecutionMessageCodec executionMessageCodec) {
		this.photoSimilarityService = photoSimilarityService;
		this.videoSimilarityService = videoSimilarityService;
		this.executionEnqueueService = executionEnqueueService;
		this.executionPayloadCodec = executionPayloadCodec;
		this.executionMessageCodec = executionMessageCodec;
	}

	public Execution launchPhotos(int minSimilarityPercent) {
		return launch(ExecutionType.SIMILARITY_PHOTO, photoSimilarityService, minSimilarityPercent);
	}

	public Execution launchVideos(int minSimilarityPercent) {
		return launch(ExecutionType.SIMILARITY_VIDEO, videoSimilarityService, minSimilarityPercent);
	}

	/**
	 * Queues the analysis, or hands back the one already queued for the same work.
	 *
	 * <p>
	 * "The same work" is the whole identity: the family - medium, algorithm,
	 * grouping version and every effective parameter, exclusions included - plus
	 * the composition the requester sees. Two clicks at the same threshold over the
	 * same library are one analysis; a different threshold, a changed exclusion or
	 * a photo that arrived in between are different work and get their own.
	 *
	 * <p>
	 * The key is built from the same values the payload carries, so the execution
	 * is deduplicated by exactly the intention the handler will check before
	 * running. A key that described one analysis while the handler performed
	 * another is the failure this pairing exists to prevent.
	 */
	private Execution launch(ExecutionType type, SimilarityAnalyzer analyzer, int minSimilarityPercent) {
		int minimum = SimilarityBounds.clamp(minSimilarityPercent);

		SimilarityFamily family = analyzer.family(minimum);

		SimilarityComposition composition = analyzer.composition();

		ExecutionMessage started = SimilarityMessages.analysisStarted(composition.analyzedCount(),
				composition.eligibleCount());

		return executionEnqueueService.enqueueOrExisting(Execution.builder().executionType(type).recursive(false)
				.executeFlag(true).filesFound(composition.analyzedCount())
				.dedupKey(dedupKey(family, composition))
				.requestPayload(executionPayloadCodec.encode(new SimilarityAnalysisPayload(
						DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION, minimum, family.parametersDigest(),
						composition.digest())))
				.statusMessage(StatusMessage.coded(started.code(), executionMessageCodec.encode(started.args())))
				.build());
	}

	/**
	 * The identity of the work, short enough for the column that indexes it: two
	 * digests and the version, rather than the parameters themselves.
	 */
	private String dedupKey(SimilarityFamily family, SimilarityComposition composition) {
		return family.mediaType().name() + ':' + family.algorithmId() + ':' + family.groupingVersion() + ':'
				+ family.parametersDigest() + ':' + composition.digest();
	}
}