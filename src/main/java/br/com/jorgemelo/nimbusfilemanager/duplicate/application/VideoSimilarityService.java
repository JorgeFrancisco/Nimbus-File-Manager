package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateFileResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.GroupParts;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.PairKey;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarVideoGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityComposition;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFamily;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoCandidate;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoFrameHash;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoSignature;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.CompositionRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.MediaQuality;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoFrameRawResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.VideoSimilarityProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PageUtils;

/**
 * Finds visually related videos. It reassembles each video's sampled frames
 * into a {@link VideoSignature}, buckets candidates by approximate duration so
 * it never runs an all-pairs O(n^2) comparison, and delegates the actual
 * comparison/aggregation to the pluggable {@link VideoSimilarityAlgorithm}
 * (frame pHash pre-filter + SSIM + trimmed mean + concordant-frame quorum).
 *
 * <p>
 * Like its photo counterpart it is an engine and never its own trigger: what
 * runs it is a queued execution, and what a screen reads is the published
 * result.
 */
@Service
@Transactional(readOnly = true)
class VideoSimilarityService implements SimilarityAnalyzer {

	private final MediaFingerprintRepository mediaFingerprintRepository;
	private final DuplicateGroupAssembler duplicateGroupAssembler;
	private final VideoSimilarityAlgorithm algorithm;
	private final DuplicateExclusionService duplicateExclusionService;
	private final VideoSimilarityProperties videoSimilarityProperties;

	VideoSimilarityService(MediaFingerprintRepository mediaFingerprintRepository,
			DuplicateGroupAssembler duplicateGroupAssembler, VideoSimilarityAlgorithm algorithm,
			DuplicateExclusionService duplicateExclusionService,
			VideoSimilarityProperties videoSimilarityProperties) {
		this.mediaFingerprintRepository = mediaFingerprintRepository;
		this.duplicateGroupAssembler = duplicateGroupAssembler;
		this.algorithm = algorithm;
		this.duplicateExclusionService = duplicateExclusionService;
		this.videoSimilarityProperties = videoSimilarityProperties;
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
	 * Every effective parameter of the video comparison - the normalized values,
	 * not what configuration happens to say, so a value clamped to its bound
	 * produces the digest of the bound.
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
				.with("candidateLimit", candidateLimit())
				.with("selectionPolicy", SimilarityConstants.SELECTION_OLDEST_FIRST)
				.with("exclusions", duplicateExclusionService.signature()).digest();
	}

	@Override
	public int eligibleCount() {
		return mediaFingerprintRepository.countEligibleForSimilarity(algorithm.kind().name(), algorithm.algorithm());
	}

	@Override
	public int candidateLimit() {
		return videoSimilarityProperties.maxCandidatesOrDefault();
	}

	@Override
	public SimilarityComposition composition() {
		return compositionOf(SimilarityGroupSupport.canonicalComposition(
				mediaFingerprintRepository.findVideoCompositionRows(algorithm.kind(), algorithm.algorithm(),
						PageUtils.firstPage(rowCap())),
				CompositionRow::mediaPublicId, CompositionRow::currentFolder, duplicateExclusionService));
	}

	private SimilarityComposition compositionOf(List<CompositionRow> rows) {
		return new SimilarityComposition(
				SimilarityDigest.ofComposition(rows.stream().map(CompositionRow::mediaPublicId).toList(),
						rows.stream().map(CompositionRow::currentFolder).toList()),
				eligibleCount(), rows.size(), candidateLimit(), SimilarityConstants.SELECTION_OLDEST_FIRST);
	}

	@Override
	public SimilarityAnalysisResult analyze(int minSimilarityPercent, SimilarityProgressCallback progress) {
		int minimum = SimilarityBounds.clamp(minSimilarityPercent);

		List<VideoFrameRawResponse> rows = mediaFingerprintRepository.findFingerprintedVideoFrames(algorithm.kind(),
				algorithm.algorithm(), PageUtils.firstPage(rowCap()));

		// One selection, the same primitive the application calls: it collapses the
		// frame rows to their videos in order - including the one the cap cut short -
		// and the heavy rows are then kept for exactly those videos.
		List<CompositionRow> selected = SimilarityGroupSupport.canonicalComposition(rows, VideoFrameRawResponse::id,
				VideoFrameRawResponse::currentFolder, duplicateExclusionService);

		Set<UUID> analysed = selected.stream().map(CompositionRow::mediaPublicId).collect(Collectors.toSet());

		List<VideoCandidate> candidates = reassemble(rows.stream().filter(row -> analysed.contains(row.id())).toList());

		List<SimilarVideoGroupResponse> responses = group(candidates, minimum, progress);

		SimilarityComposition composition = compositionOf(selected);

		List<AnalyzedGroup> groups = responses.stream()
				.map(response -> SimilarityGroupSupport.toAnalyzedGroup(response.similarityPercent(),
						response.wastedSize().bytes(), response.keep(), response.deleteCandidates(),
						response.reviewCandidates()))
				.toList();

		return new SimilarityAnalysisResult(family(minimum), composition, groups);
	}

	/** The clustering itself, over the candidates the selection kept. */
	private List<SimilarVideoGroupResponse> group(List<VideoCandidate> candidates, int minimum,
			SimilarityProgressCallback progress) {
		List<UUID> allIds = candidates.stream().map(candidate -> candidate.signature().id()).toList();

		Map<UUID, MediaQuality> quality = duplicateGroupAssembler.qualityByPublicId(allIds);

		Map<UUID, Set<Long>> buckets = precomputeBuckets(candidates);

		Map<PairKey, Integer> scores = new HashMap<>();

		List<List<VideoCandidate>> groups = SimilarityCompleteLinkageGrouper.cluster(candidates, minimum,
				(first, second) -> score(first, second, minimum, scores, buckets), progress);

		return groups.stream().map(group -> toResponse(group, minimum, scores, buckets, quality))
				.sorted((first, second) -> Long.compare(second.wastedSize().bytes(), first.wastedSize().bytes()))
				.toList();
	}

	/**
	 * Reassembles the per-frame rows (ordered by file then sampleIndex) per video.
	 */
	private List<VideoCandidate> reassemble(List<VideoFrameRawResponse> rows) {
		List<VideoCandidate> candidates = new ArrayList<>();

		UUID currentId = null;

		List<VideoFrameHash> frames = new ArrayList<>();

		VideoFrameRawResponse head = null;

		for (VideoFrameRawResponse row : rows) {
			if (!row.id().equals(currentId)) {
				if (head != null) {
					candidates.add(toCandidate(head, frames));
				}

				currentId = row.id();

				frames = new ArrayList<>();

				head = row;
			}

			frames.add(new VideoFrameHash(row.sampleIndex(), row.phash(), row.luminance()));
		}

		if (head != null) {
			candidates.add(toCandidate(head, frames));
		}

		return candidates;
	}

	private VideoCandidate toCandidate(VideoFrameRawResponse head, List<VideoFrameHash> frames) {
		VideoSignature signature = new VideoSignature(head.id(), List.copyOf(frames), head.durationSeconds(),
				head.width(), head.height());

		return new VideoCandidate(signature, head.fileName(), head.extension(), head.sizeBytes(), head.currentPath(),
				head.currentFolder(), head.modifiedAt());
	}

	private Map<UUID, Set<Long>> precomputeBuckets(List<VideoCandidate> candidates) {
		Map<UUID, Set<Long>> buckets = new HashMap<>();

		for (VideoCandidate candidate : candidates) {
			buckets.put(candidate.signature().id(), algorithm.candidateBuckets(candidate.signature()));
		}

		return buckets;
	}

	/**
	 * Bucket-gated similarity: a cheap disjoint-bucket check rejects distant videos
	 * before any frame comparison, so the expensive SSIM only runs for videos that
	 * share a duration bucket. Memoized per pair for the duration of one grouping.
	 */
	private int score(VideoCandidate first, VideoCandidate second, int minimum, Map<PairKey, Integer> scores,
			Map<UUID, Set<Long>> buckets) {
		UUID firstId = first.signature().id();
		UUID secondId = second.signature().id();

		if (Collections.disjoint(buckets.get(firstId), buckets.get(secondId))) {
			return -1;
		}

		return scores.computeIfAbsent(PairKey.of(firstId, secondId),
				_ -> algorithm.similarityPercent(first.signature(), second.signature(), minimum));
	}

	/**
	 * The threshold passed on is the one this analysis ran under, not the global
	 * floor it used to be. No result changes: complete linkage means every pair of
	 * a group was already compared while it was formed, so every score
	 * {@code worstScore} asks for is a memo hit and nothing is recomputed. The
	 * floor was simply the wrong value to name in a place that answers for one
	 * analysis (audit note: V29, refuted as a defect, kept as clarity).
	 */
	private SimilarVideoGroupResponse toResponse(List<VideoCandidate> group, int minimum, Map<PairKey, Integer> scores,
			Map<UUID, Set<Long>> buckets, Map<UUID, MediaQuality> quality) {
		List<DuplicateFileResponse> files = group.stream().map(this::toFileResponse).toList();

		GroupParts parts = duplicateGroupAssembler.assemble(files, quality, false);

		return new SimilarVideoGroupResponse(String.valueOf(parts.keep().id()), group.size(),
				SimilarityCompleteLinkageGrouper.worstScore(group,
						(first, second) -> score(first, second, minimum, scores, buckets)),
				SizeResponse.of(parts.wastedBytes()), parts.keep(), parts.deleteCandidates(), parts.reviewCandidates());
	}

	private DuplicateFileResponse toFileResponse(VideoCandidate candidate) {
		return new DuplicateFileResponse(candidate.signature().id(), candidate.fileName(), candidate.extension(),
				"VIDEO", SizeResponse.of(candidate.sizeBytes()), candidate.currentPath(), candidate.currentFolder(),
				candidate.modifiedAt());
	}

	private int rowCap() {
		return videoSimilarityProperties.maxCandidatesOrDefault() * algorithm.framesPerFingerprint();
	}

}