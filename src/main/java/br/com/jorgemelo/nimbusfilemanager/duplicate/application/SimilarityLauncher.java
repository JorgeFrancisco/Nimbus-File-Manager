package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityMessages;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisPayload;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityComposition;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFamily;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.SimilarityRunMode;
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
	 * Brings every answer that exists up to date with the photos that arrived.
	 *
	 * <p>
	 * One request per threshold somebody has analysed, and none at all until
	 * somebody has - a library being filled for the first time queues nothing,
	 * because there is no answer yet for an arrival to bring up to date. The
	 * thresholds come from the coverage rather than from a setting: which
	 * threshold to use is a per-user screen preference and there is no user here,
	 * but which answers exist is a fact the product wrote down.
	 */
	public void refreshPhotosAfterArrival() {
		for (int threshold : photoSimilarityService.analysedThresholds()) {
			addPhotos(threshold);
		}
	}

	/**
	 * The same for videos, and deliberately the same code below it: what differs
	 * between the two is the execution type and which analyser is asked, which is
	 * the pair {@link #add} takes.
	 */
	public void refreshVideosAfterArrival() {
		for (int threshold : videoSimilarityService.analysedThresholds()) {
			addVideos(threshold);
		}
	}

	/**
	 * Brings every family that has already been analysed back in line with a set
	 * of files that changed while the relations between them did not.
	 *
	 * <p>
	 * A regroup rather than a rebuild because nothing here invalidates a stored
	 * relation: excluding a file, quarantining it or moving it out of an included
	 * folder changes who takes part, not how alike any two of them are. The
	 * comparison that a rebuild would redo - the one that costs the minutes - has
	 * an answer already, and it is still the right answer.
	 *
	 * <p>
	 * Only families with an analysis behind them. A threshold nobody ever ran is
	 * not brought into existence by an exclusion: the first analysis of a family
	 * is a decision the user makes, and turning an eligibility change into one
	 * would start minutes of work nobody asked for.
	 */
	void refreshAfterEligibilityChange() {
		for (int threshold : photoSimilarityService.analysedThresholds()) {
			regroup(ExecutionType.SIMILARITY_PHOTO, photoSimilarityService, threshold);
		}

		for (int threshold : videoSimilarityService.analysedThresholds()) {
			regroup(ExecutionType.SIMILARITY_VIDEO, videoSimilarityService, threshold);
		}
	}

	public Execution addVideos(int minSimilarityPercent) {
		return add(ExecutionType.SIMILARITY_VIDEO, videoSimilarityService, minSimilarityPercent);
	}

	/**
	 * Asks for the photos that arrived to be incorporated into the answer.
	 *
	 * <p>
	 * <b>Deduplicated by the family alone</b>, without the composition that a
	 * rebuild's key carries - and that single difference is what stops a phone
	 * backup from publishing once per photo. A rebuild is about a snapshot, so two
	 * requests over different snapshots are different work; an arrival is about
	 * <em>whatever is new when it starts</em>, so two of them are the same work and
	 * the second has nothing to add to the first. Including the composition would
	 * make three hundred photos three hundred analyses of ever-growing libraries,
	 * each superseding the last.
	 *
	 * <p>
	 * The coalescing itself is the queue's, not a timer's. The 1 + 1 rule already
	 * allows exactly one request of a key to wait while one runs, so photos
	 * arriving during a run collect into the single successor, and photos arriving
	 * while that successor waits are absorbed by it - it discovers what is new when
	 * it starts, which is later than now. Nothing is lost and nothing is
	 * rescheduled: the waiting row is the promise that everything queued behind it
	 * will be looked at.
	 *
	 * <p>
	 * The composition is left out of the payload for the same reason. A rebuild
	 * reports when the world moved under it, because it was asked about a
	 * particular world; an arrival was asked about the difference, and the world
	 * moving is what it is for.
	 */
	public Execution addPhotos(int minSimilarityPercent) {
		return add(ExecutionType.SIMILARITY_PHOTO, photoSimilarityService, minSimilarityPercent);
	}

	private Execution add(ExecutionType type, SimilarityAnalyzer analyzer, int minSimilarityPercent) {
		return incremental(type, analyzer, minSimilarityPercent, SimilarityRunMode.ADD);
	}

	private Execution regroup(ExecutionType type, SimilarityAnalyzer analyzer, int minSimilarityPercent) {
		return incremental(type, analyzer, minSimilarityPercent, SimilarityRunMode.REGROUP);
	}

	/**
	 * The two modes that are about a difference rather than about a snapshot, and
	 * which therefore name no composition: an arrival finds out what is new when
	 * it starts, a regroup finds out who takes part when it starts.
	 */
	private Execution incremental(ExecutionType type, SimilarityAnalyzer analyzer, int minSimilarityPercent,
			SimilarityRunMode mode) {
		int minimum = SimilarityBounds.clamp(minSimilarityPercent);

		SimilarityFamily family = analyzer.family(minimum);

		SimilarityComposition composition = analyzer.composition();

		ExecutionMessage started = SimilarityMessages.analysisStarted(composition.analyzedCount(),
				composition.eligibleCount());

		return executionEnqueueService.enqueueOrExisting(Execution.builder().executionType(type).recursive(false)
				.executeFlag(true).filesFound(composition.analyzedCount()).dedupKey(modeDedupKey(family, mode))
				.requestPayload(executionPayloadCodec.encode(new SimilarityAnalysisPayload(
						DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION, minimum, family.parametersDigest(),
						null, mode)))
				.statusMessage(StatusMessage.coded(started.code(), executionMessageCodec.encode(started.args())))
				.build());
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
						composition.digest(), SimilarityRunMode.REBUILD)))
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

	/**
	 * The identity of "bring this family up to date", which names no composition
	 * and is therefore the same key however many photos arrive or however many
	 * files are excluded.
	 *
	 * <p>
	 * Ends in the mode rather than in a digest, so it can never collide with the
	 * key above, nor the two incremental modes with each other: a rebuild, an
	 * arrival and a regroup over the same family are three different pieces of
	 * work, and answering one with another would either analyse a snapshot nobody
	 * asked about, skip a comparison somebody did, or recompare what nothing
	 * invalidated.
	 */
	private String modeDedupKey(SimilarityFamily family, SimilarityRunMode mode) {
		return family.mediaType().name() + ':' + family.algorithmId() + ':' + family.groupingVersion() + ':'
				+ family.parametersDigest() + ':' + mode;
	}
}