package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateFileResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.GroupParts;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.RelationParameters;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarVideoGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityComposition;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFamily;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoCandidate;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityRelationRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.CompositionRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.MediaQuality;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoFrameRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityRelationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.VideoSimilarityProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * Finds visually related videos. It has the frame rows reassembled into
 * per-video signatures and delegates the comparison to the pluggable
 * {@link VideoSimilarityAlgorithm} (duration and aspect gates, frames aligned
 * by {@code sampleIndex}, pHash pre-filter, SSIM, trimmed mean,
 * concordant-frame quorum). What it does with the answers is the shared
 * machinery photos use: approved pairs are stored as relations, coverage
 * records which videos those relations account for, and the grouping reads them
 * instead of recomputing.
 *
 * <p>
 * Like its photo counterpart it is an engine and never its own trigger: what
 * runs it is a queued execution, and what a screen reads is the published
 * result.
 *
 * <p>
 * <b>No transaction of its own, deliberately, and not even a read-only one.</b>
 * It carried {@code @Transactional(readOnly = true)} at class level, and on
 * PostgreSQL that is not a hint: it reaches the connection as
 * {@code SET TRANSACTION READ ONLY}, and the driver then refuses every insert
 * made under it - so the relations a rebuild approves could not be stored at
 * all. Photos hit exactly that and the annotation was removed there; it is
 * removed here for the same reason, at the moment videos gained something to
 * write. The only write goes through {@link SimilarityRelationWriter}, which
 * opens the short transaction where the relations and the coverage that
 * accounts for them are made atomic.
 */
@Slf4j
@Service
class VideoSimilarityService implements SimilarityAnalyzer, SimilarityRegrouper, SimilarityAdder {

	private final MediaFingerprintRepository mediaFingerprintRepository;
	private final DuplicateGroupAssembler duplicateGroupAssembler;
	private final VideoSimilarityAlgorithm algorithm;
	private final DuplicateExclusionService duplicateExclusionService;
	private final VideoSimilarityProperties videoSimilarityProperties;
	private final SimilarityRelationWriter similarityRelationWriter;
	private final SimilarityRelationRepository similarityRelationRepository;

	VideoSimilarityService(MediaFingerprintRepository mediaFingerprintRepository,
			DuplicateGroupAssembler duplicateGroupAssembler, VideoSimilarityAlgorithm algorithm,
			DuplicateExclusionService duplicateExclusionService, VideoSimilarityProperties videoSimilarityProperties,
			SimilarityRelationWriter similarityRelationWriter,
			SimilarityRelationRepository similarityRelationRepository) {
		this.mediaFingerprintRepository = mediaFingerprintRepository;
		this.duplicateGroupAssembler = duplicateGroupAssembler;
		this.algorithm = algorithm;
		this.duplicateExclusionService = duplicateExclusionService;
		this.videoSimilarityProperties = videoSimilarityProperties;
		this.similarityRelationWriter = similarityRelationWriter;
		this.similarityRelationRepository = similarityRelationRepository;
	}

	@Override
	public FileType mediaType() {
		return FileType.VIDEO;
	}

	@Override
	public SimilarityFamily family(int minSimilarityPercent) {
		return new SimilarityFamily(FileType.VIDEO, algorithm.algorithm(), SimilarityConstants.GROUPING_VERSION,
				parametersDigest(SimilarityBounds.clamp(minSimilarityPercent)));
	}

	/**
	 * Every effective parameter of the video grouping - the normalized values, not
	 * what configuration happens to say, so a value clamped to its bound produces
	 * the digest of the bound.
	 *
	 * <p>
	 * {@code framesPerFingerprint} belongs to the algorithm's own identity and is
	 * included anyway: it decides how many frames align, which changes the result
	 * even when the algorithm string does not move.
	 */
	private String parametersDigest(int minimum) {
		return new SimilarityParameters().with("minSimilarity", minimum)
				.with("minConcordantFrames", videoSimilarityProperties.minConcordantFramesOrDefault())
				.with("trimmedLowestFrames", videoSimilarityProperties.trimmedLowestFramesOrDefault())
				.with("maxFrameHashDistance", videoSimilarityProperties.maxFrameHashDistanceOrDefault())
				.with("durationToleranceSeconds", videoSimilarityProperties.durationToleranceSecondsOrDefault())
				.with("aspectRatioTolerance", videoSimilarityProperties.aspectRatioToleranceOrDefault())
				.with("framesPerFingerprint", algorithm.framesPerFingerprint())
				// The candidate cap is gone from both media, so it is no longer an effective
				// parameter of anything and no longer belongs in the identity. Its removal is
				// what makes every previously published video analysis stop being read: the
				// digest is the family's identity, so a result computed over the first 8.000
				// videos lives on as its own family rather than being served as an answer about
				// the whole library. That is the honest outcome, and it costs one full
				// analysis.
				.with("selectionPolicy", SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST)
				.with("exclusions", duplicateExclusionService.signature()).digest();
	}

	/**
	 * What a video relation is keyed by: the algorithm, the frame radius and the
	 * threshold in the shared columns, and the rest of what decides the verdict in
	 * the digest beside them.
	 *
	 * <p>
	 * The four in the digest are the ones a photo does not have and a video cannot
	 * do without - the quorum and the trim decide whether an approval survives, the
	 * two tolerances decide whether the pair is compared at all. What is
	 * deliberately absent is {@code framesPerFingerprint}: it is already part of
	 * the algorithm string (a name ending in {@code _FRAMES_V1} promises five), and
	 * a value carried in two places is a value that can disagree with itself.
	 *
	 * <p>
	 * Also absent is everything the family's digest carries beyond this - the
	 * candidate limit, the selection policy, the exclusion signature. Those decide
	 * which videos enter an analysis, not whether two of them look alike, so a
	 * hidden folder must not throw away facts that did not change.
	 */
	private RelationParameters relationParameters(int minimum) {
		return new RelationParameters(algorithm.algorithm(), videoSimilarityProperties.maxFrameHashDistanceOrDefault(),
				minimum, relationDigest());
	}

	/** The medium-specific half of the key, which no threshold takes part in. */
	private String relationDigest() {
		return new SimilarityParameters()
				.with("minConcordantFrames", videoSimilarityProperties.minConcordantFramesOrDefault())
				.with("trimmedLowestFrames", videoSimilarityProperties.trimmedLowestFramesOrDefault())
				.with("durationToleranceSeconds", videoSimilarityProperties.durationToleranceSecondsOrDefault())
				.with("aspectRatioTolerance", videoSimilarityProperties.aspectRatioToleranceOrDefault()).digest();
	}

	@Override
	public int eligibleCount() {
		return mediaFingerprintRepository.countEligibleForSimilarity(algorithm.kind().name(), algorithm.algorithm());
	}

	/**
	 * The thresholds this installation has actually analysed videos at, read from
	 * the coverage rather than from a setting - the question an arrival raises is
	 * not what somebody would choose but which answers exist and are now out of
	 * date.
	 */
	List<Integer> analysedThresholds() {
		return similarityRelationRepository.findAnalysedThresholds(algorithm.algorithm(),
				videoSimilarityProperties.maxFrameHashDistanceOrDefault(), relationDigest());
	}

	@Override
	public SimilarityComposition composition() {
		return compositionOf(SimilarityGroupSupport.canonicalComposition(
				mediaFingerprintRepository.findVideoCompositionRows(algorithm.kind(), algorithm.algorithm(),
						eligibleIds()),
				CompositionRow::mediaPublicId, CompositionRow::currentFolder, duplicateExclusionService));
	}

	/**
	 * Every eligible video, in catalog order, with nothing cut off the end.
	 *
	 * <p>
	 * There used to be a cap of 8.000 here, and what it did was truncate: the
	 * analysis ran over the first 8.000 eligible videos and published the answer as
	 * though it were about the library. The number was never derived from a
	 * resource - the measurements retired that reason. A hundred thousand videos
	 * hold 619 MB of frames against the worker's 4 GB, and the frames are read once
	 * rather than kept per pair, so memory was never what the cap protected.
	 *
	 * <p>
	 * What scale really costs is time, and it costs it once: a first full analysis
	 * of a hundred thousand videos is tens of minutes of comparison. That is an
	 * execution in the queue - it reports progress, it can be cancelled, and the
	 * previous answer stays readable until the new one is promoted. Every run after
	 * it is an arrival, and an arrival compares {@code N x C + N x N} where a
	 * rebuild compares {@code C x (C - 1) / 2} more: measured at 19,9 ms for one
	 * arriving video against a library of 5.794. Trading a permanent silent
	 * truncation for a one-off long execution is the trade the product wants.
	 *
	 * <p>
	 * The query takes no limit at all any more - neither medium caps, so there is
	 * nothing to pass and nothing that could be passed by mistake.
	 */
	private List<Long> eligibleIds() {
		return mediaFingerprintRepository.findEligibleForSimilarity(algorithm.kind().name(), algorithm.algorithm());
	}

	private SimilarityComposition compositionOf(List<CompositionRow> rows) {
		return new SimilarityComposition(
				SimilarityDigest.ofComposition(rows.stream().map(CompositionRow::mediaPublicId).toList(),
						rows.stream().map(CompositionRow::currentFolder).toList()),
				eligibleCount(), rows.size(), SimilarityConstants.NO_CANDIDATE_LIMIT,
				SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST);
	}

	@Override
	public SimilarityAnalysisResult analyze(int minSimilarityPercent, SimilarityProgressCallback progress) {
		int minimum = SimilarityBounds.clamp(minSimilarityPercent);

		List<VideoFrameRawResponse> rows = mediaFingerprintRepository.findFingerprintedVideoFrames(algorithm.kind(),
				algorithm.algorithm(), eligibleIds());

		// One selection, the same primitive the application calls: it collapses the
		// frame rows to their videos in order, and the heavy rows are then kept for
		// exactly those videos.
		List<CompositionRow> selected = SimilarityGroupSupport.canonicalComposition(rows, VideoFrameRawResponse::id,
				VideoFrameRawResponse::currentFolder, duplicateExclusionService);

		Set<UUID> analysed = selected.stream().map(CompositionRow::mediaPublicId).collect(Collectors.toSet());

		List<VideoCandidate> candidates = VideoCandidateAssembler
				.assemble(rows.stream().filter(row -> analysed.contains(row.id())).toList());

		List<SimilarVideoGroupResponse> responses = group(candidates, minimum, progress);

		return new SimilarityAnalysisResult(family(minimum), compositionOf(selected), toAnalyzedGroups(responses));
	}

	/**
	 * The same answer, from the relations already approved - nothing compared.
	 *
	 * <p>
	 * What a removal leaves behind is a set of relations that is still entirely
	 * true. Whether two videos look alike does not depend on which other videos
	 * exist, so the ones naming a file that left are simply not read and the rest
	 * need no revisiting. The grouping itself <em>is</em> run again, over
	 * everything: the placement is greedy, so a video can be the reason another was
	 * refused, and its departure has to let that refusal be taken again.
	 */
	@Override
	public SimilarityAnalysisResult regroup(int minSimilarityPercent, SimilarityProgressCallback progress) {
		int minimum = SimilarityBounds.clamp(minSimilarityPercent);

		return regroupOver(eligibleIds(), minimum, progress);
	}

	/**
	 * Everything that arrived since the last run, incorporated - and then the same
	 * grouping a removal gets.
	 *
	 * <p>
	 * The two halves are deliberately unequal. The first compares, and it is the
	 * only part that can discover anything: the videos nobody has incorporated yet
	 * against every video that was, and against each other. The second compares
	 * nothing and simply reads back what is stored for the eligible set.
	 *
	 * <p>
	 * The order of the two halves is the transactional rule this rests on:
	 * relations and coverage are written together, and coverage is never granted
	 * for a video whose relations are not stored. A crash between them leaves the
	 * video new, which costs a repeat; the other way round would leave a pair
	 * nobody ever evaluates.
	 */
	@Override
	public SimilarityAnalysisResult add(int minSimilarityPercent, SimilarityProgressCallback progress) {
		int minimum = SimilarityBounds.clamp(minSimilarityPercent);

		List<Long> eligible = eligibleIds();

		RelationParameters parameters = relationParameters(minimum);

		List<Long> newcomers = similarityRelationRepository.findEligibleNotCovered(parameters.algorithmId(),
				parameters.maxDistance(), parameters.minSimilarity(), parameters.relationDigest(),
				eligible.toArray(Long[]::new));

		if (newcomers.isEmpty()) {
			return regroupOver(eligible, minimum, progress);
		}

		List<Long> covered = similarityRelationRepository.findCovered(parameters.algorithmId(),
				parameters.maxDistance(), parameters.minSimilarity(), parameters.relationDigest());

		if (rebuildIsCheaper(newcomers.size(), covered.size())) {
			log.info(
					"{} video(s) are not yet incorporated against {} that are, so a full rebuild costs less than"
							+ " incorporating them one against the other; running the rebuild",
					newcomers.size(), covered.size());

			return analyze(minimum, progress);
		}

		incorporate(parameters, newcomers, covered, minimum, progress);

		return regroupOver(eligible, minimum, progress);
	}

	/**
	 * Which of the two equivalent routes is the cheap one, counted in pairs rather
	 * than in iterations - and that is where the video answer parts company with
	 * the photo one.
	 *
	 * <p>
	 * A photo rebuild's cost is dominated by a scan that visits every pair whether
	 * or not it survives, so the photo comparison prices <em>iterations</em> and
	 * lands on "cheaper while the newcomers are fewer than the covered". A video
	 * pair is not like that: the cheap signals reject it before any frame is read,
	 * and the ones that survive cost three orders of magnitude more than the ones
	 * that do not. So both routes are priced by the pairs they evaluate, which is
	 * what the cost is actually proportional to.
	 *
	 * <p>
	 * <b>It is not that an arrival ever examines more pairs.</b> An arrival
	 * evaluates {@code N x C + N x (N - 1) / 2} and a rebuild
	 * {@code T x (T - 1) / 2} for {@code T = N + C}, and the difference between
	 * them is exactly {@code C x (C - 1) / 2} - the pairs of two covered videos
	 * that an arrival skips because neither was touched. That is never negative, so
	 * the comparison work of an arrival is bounded above by a rebuild's for every
	 * {@code N}, which the measurement confirms: at {@code N = 1.000} against
	 * {@code C = 4.795} the comparison took 1,3 s where the rebuild took 3,3 s.
	 *
	 * <p>
	 * What decides the route is therefore the saving against what an arrival costs
	 * on top. An arrival reads the whole library's gate rows, then issues a second
	 * query for the frames the survivors named, then reads the stored relations
	 * back, and writes by upsert where a rebuild writes one bulk replace. The
	 * saving it buys with that is {@code C x (C - 1) / 2} out of
	 * {@code T x (T - 1) / 2}, and once {@code N > C} the covered set is under half
	 * the library, so the saving is under a quarter of the work - which is where it
	 * stops covering the extra reads. Hence the rule, which is the same shape as
	 * the photo one and cheap to evaluate before the run: two counts already in
	 * hand.
	 *
	 * <p>
	 * The tie goes to the arrival: it adds to the stored relations, where a rebuild
	 * deletes the family's set and writes it again.
	 *
	 * <p>
	 * The case this exists for is the first analysis of a library, and the one
	 * after a fingerprint rebuild: everything is a newcomer and nothing is covered,
	 * so the arrival route would compare the whole library against itself while
	 * carrying the two-phase machinery, which is strictly more work than the
	 * rebuild it is trying to avoid.
	 */
	private boolean rebuildIsCheaper(int newcomers, int covered) {
		return newcomers > covered;
	}

	/**
	 * The comparing half of an arrival: the pairs the newcomers create, and the
	 * record that they are now part of the universe.
	 */
	private void incorporate(RelationParameters parameters, List<Long> newcomers, List<Long> covered, int minimum,
			SimilarityProgressCallback progress) {
		ArrivingRelations arriving = new VideoArrivalRelationBuilder(mediaFingerprintRepository, algorithm)
				.build(parameters, newcomers, covered, minimum, progress);

		BuiltRelations built = arriving.relations();

		similarityRelationWriter.save(parameters, built.first(), built.second(), built.scores(), built.count(),
				arriving.catalogFileIds(), arriving.newlyCovered());

		log.info(
				"Incorporated {} arriving video(s) against {} already covered: {} pair(s) past the gates,"
						+ " {} video(s) read in full, {} relation(s) approved",
				arriving.newlyCovered().length, covered.size(), arriving.candidatePairs(), arriving.samplesLoaded(),
				built.count());
	}

	/**
	 * The grouping over what is stored, which is the second half of an arrival and
	 * the whole of a removal.
	 *
	 * @param eligible the videos the answer is to be about, ascending - passed in
	 * rather than read again so that the relations, the composition and the
	 * arrivals are all about one set
	 */
	private SimilarityAnalysisResult regroupOver(List<Long> eligible, int minimum,
			SimilarityProgressCallback progress) {
		RelationParameters parameters = relationParameters(minimum);

		StoredRelations stored = StoredRelations.of(
				similarityRelationRepository.findEligibleRelations(parameters.algorithmId(), parameters.maxDistance(),
						parameters.minSimilarity(), parameters.relationDigest(), eligible.toArray(Long[]::new)));

		List<VideoFrameRawResponse> rows = mediaFingerprintRepository.findFingerprintedVideoFrames(algorithm.kind(),
				algorithm.algorithm(), stored.participants());

		// The same late look at the exclusions the rebuild takes, for the same reason:
		// the query applied them when it chose the eligible ids, and this only differs
		// if the user hid something in between.
		List<VideoFrameRawResponse> related = SimilarityGroupSupport.withoutExcluded(rows, duplicateExclusionService,
				VideoFrameRawResponse::id, VideoFrameRawResponse::currentFolder);

		List<VideoCandidate> candidates = VideoCandidateAssembler.assemble(related);

		List<SimilarVideoGroupResponse> responses = group(candidates, stored, progress);

		List<CompositionRow> selected = SimilarityGroupSupport.canonicalComposition(
				mediaFingerprintRepository.findVideoCompositionRows(algorithm.kind(), algorithm.algorithm(), eligible),
				CompositionRow::mediaPublicId, CompositionRow::currentFolder, duplicateExclusionService);

		return new SimilarityAnalysisResult(family(minimum), compositionOf(selected), toAnalyzedGroups(responses));
	}

	/**
	 * Comparison first, grouping second - and each pair compared once.
	 *
	 * <p>
	 * The two steps used to be one: the grouping called the scorer from inside its
	 * own loop, and because every candidate is offered to every cluster it asked
	 * about most pairs many times over. Splitting them changes no verdict -
	 * {@code VideoRelationGroupingEquivalenceTest} holds the two groupings to
	 * identical output over directed cases and hundreds of random populations - it
	 * only stops the work being repeated, and leaves the approvals in a form worth
	 * storing.
	 */
	private List<SimilarVideoGroupResponse> group(List<VideoCandidate> candidates, int minimum,
			SimilarityProgressCallback progress) {
		Map<UUID, MediaQuality> quality = qualityOf(candidates);

		BuiltRelations built = new VideoRelationBuilder(algorithm).build(VideoCandidateAssembler.signatures(candidates),
				minimum, progress);

		persist(built, candidates, minimum);

		ApprovedRelations relations = built.relations();

		return toResponses(SimilarityRelationGrouper.cluster(candidates.size(), relations, (_, _) -> {
		}), candidates, relations, quality);
	}

	/**
	 * The grouping alone, over relations that were approved by an earlier run.
	 *
	 * <p>
	 * The candidates decide the positions and the relations are indexed against
	 * them, rather than the other way round: the rows arrive in
	 * {@code catalog_file.id} order, which is the order the greedy placement
	 * depends on, and a relation naming a video the load did not return is dropped
	 * instead of shifting every position after it.
	 */
	private List<SimilarVideoGroupResponse> group(List<VideoCandidate> candidates, StoredRelations stored,
			SimilarityProgressCallback progress) {
		progress.update(0, candidates.size());

		long[] nodes = new long[candidates.size()];

		for (int index = 0; index < candidates.size(); index++) {
			nodes[index] = candidates.get(index).catalogFileId();
		}

		Map<UUID, MediaQuality> quality = qualityOf(candidates);

		ApprovedRelations relations = stored.indexedBy(nodes);

		List<List<Integer>> clusters = SimilarityRelationGrouper.cluster(candidates.size(), relations, (_, _) -> {
		});

		progress.update(candidates.size(), candidates.size());

		return toResponses(clusters, candidates, relations, quality);
	}

	/**
	 * Writes down what this run approved, so the next change to the library does
	 * not have to rediscover it.
	 *
	 * <p>
	 * Replaced rather than merged, and that is a correctness point: only approvals
	 * are stored, so a pair that stops qualifying is simply absent from the new
	 * batch and nothing would overwrite the old row. A rebuild recomputes the whole
	 * set for these parameters, so the whole set is what it may replace.
	 */
	private void persist(BuiltRelations built, List<VideoCandidate> candidates, int minimum) {
		long[] catalogFileIds = new long[candidates.size()];

		for (int index = 0; index < candidates.size(); index++) {
			catalogFileIds[index] = candidates.get(index).catalogFileId();
		}

		similarityRelationWriter.replaceAll(relationParameters(minimum), built.first(), built.second(), built.scores(),
				built.count(), catalogFileIds);
	}

	private Map<UUID, MediaQuality> qualityOf(List<VideoCandidate> candidates) {
		return duplicateGroupAssembler
				.qualityByPublicId(candidates.stream().map(candidate -> candidate.signature().id()).toList());
	}

	private List<SimilarVideoGroupResponse> toResponses(List<List<Integer>> clusters, List<VideoCandidate> candidates,
			ApprovedRelations relations, Map<UUID, MediaQuality> quality) {
		return clusters.stream().map(group -> toResponse(group, candidates, relations, quality))
				.sorted((first, second) -> Long.compare(second.wastedSize().bytes(), first.wastedSize().bytes()))
				.toList();
	}

	private List<AnalyzedGroup> toAnalyzedGroups(List<SimilarVideoGroupResponse> responses) {
		return responses.stream()
				.map(response -> SimilarityGroupSupport.toAnalyzedGroup(response.similarityPercent(),
						response.wastedSize().bytes(), response.keep(), response.deleteCandidates(),
						response.reviewCandidates()))
				.toList();
	}

	/**
	 * The group's floor comes from the relations rather than from recomputing:
	 * every pair inside a complete-linkage group was approved to get there, so all
	 * of them are in the structure and none has to be scored again.
	 */
	private SimilarVideoGroupResponse toResponse(List<Integer> group, List<VideoCandidate> candidates,
			ApprovedRelations relations, Map<UUID, MediaQuality> quality) {
		List<DuplicateFileResponse> files = group.stream().map(candidates::get).map(this::toFileResponse).toList();

		GroupParts parts = duplicateGroupAssembler.assemble(files, quality, false);

		return new SimilarVideoGroupResponse(String.valueOf(parts.keep().id()), group.size(),
				SimilarityCompleteLinkageGrouper.worstScore(group, relations::scoreOf),
				SizeResponse.of(parts.wastedBytes()), parts.keep(), parts.deleteCandidates(), parts.reviewCandidates());
	}

	private DuplicateFileResponse toFileResponse(VideoCandidate candidate) {
		return new DuplicateFileResponse(candidate.signature().id(), candidate.fileName(), candidate.extension(),
				"VIDEO", SizeResponse.of(candidate.sizeBytes()), candidate.currentPath(), candidate.currentFolder(),
				candidate.modifiedAt());
	}
}