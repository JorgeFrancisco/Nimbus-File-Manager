package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateFileResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.GroupParts;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.PairKey;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarVideoGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoCandidate;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoFrameHash;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoSignature;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.MediaQuality;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoFrameRawResponse;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.VideoSimilarityProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PageUtils;

/**
 * Finds visually related videos. It reassembles each video's sampled frames into
 * a {@link VideoSignature}, buckets candidates by approximate duration so it
 * never runs an all-pairs O(n^2) comparison, and delegates the actual
 * comparison/aggregation to the pluggable {@link VideoSimilarityAlgorithm}
 * (frame pHash pre-filter + SSIM + trimmed mean + concordant-frame quorum). The
 * heavy grouping is cached per threshold by the shared
 * {@link SimilarityGroupCache}.
 */
@Service
@Transactional(readOnly = true)
public class VideoSimilarityService implements SimilarityGrouping {

	private final MediaFingerprintRepository mediaFingerprintRepository;
	private final DuplicateGroupAssembler duplicateGroupAssembler;
	private final VideoSimilarityAlgorithm algorithm;
	private final AppSettingService appSettingService;
	private final NimbusFileManagerProperties properties;
	private final DuplicateExclusionService duplicateExclusionService;
	private final VideoSimilarityProperties videoSimilarityProperties;

	private final SimilarityGroupCache<SimilarVideoGroupResponse> cache;

	public VideoSimilarityService(MediaFingerprintRepository mediaFingerprintRepository,
			DuplicateGroupAssembler duplicateGroupAssembler, VideoSimilarityAlgorithm algorithm,
			AppSettingService appSettingService, NimbusFileManagerProperties properties,
			DuplicateExclusionService duplicateExclusionService, VideoSimilarityProperties videoSimilarityProperties) {
		this.mediaFingerprintRepository = mediaFingerprintRepository;
		this.duplicateGroupAssembler = duplicateGroupAssembler;
		this.algorithm = algorithm;
		this.appSettingService = appSettingService;
		this.properties = properties;
		this.duplicateExclusionService = duplicateExclusionService;
		this.videoSimilarityProperties = videoSimilarityProperties;
		this.cache = new SimilarityGroupCache<>(this::fingerprintSignature, this::maxPageSize);
	}

	/** Synchronous read used by tests and as a fallback (blocking on a miss). */
	public Page<SimilarVideoGroupResponse> groups(Integer minSimilarityPercent, Pageable pageable) {
		int minimum = SimilarityBounds.clamp(minSimilarityPercent);

		if (!cache.isCached(minimum)) {
			computeAndCache(minimum, (_, _) -> {
			});
		}

		return cache.cachedPage(minimum, pageable).orElseGet(() -> cache.emptyPage(pageable));
	}

	@Override
	public boolean isCached(int minSimilarityPercent) {
		return cache.isCached(SimilarityBounds.clamp(minSimilarityPercent));
	}

	public Optional<Page<SimilarVideoGroupResponse>> cachedPage(int minSimilarityPercent, Pageable pageable) {
		return cache.cachedPage(SimilarityBounds.clamp(minSimilarityPercent), pageable);
	}

	@Override
	public void computeAndCache(int minSimilarityPercent, SimilarityProgressCallback progress) {
		int minimum = SimilarityBounds.clamp(minSimilarityPercent);

		String signature = cache.currentSignature();

		List<VideoCandidate> candidates = SimilarityGroupSupport.withoutExcluded(
				reassemble(mediaFingerprintRepository.findFingerprintedVideoFrames(algorithm.kind(),
						algorithm.algorithm(), PageUtils.firstPage(rowCap()))),
				duplicateExclusionService, candidate -> candidate.signature().id(), VideoCandidate::currentFolder);

		List<UUID> allIds = candidates.stream().map(candidate -> candidate.signature().id()).toList();

		Map<UUID, MediaQuality> quality = duplicateGroupAssembler.qualityByPublicId(allIds);

		Map<UUID, Set<Long>> buckets = precomputeBuckets(candidates);

		Map<PairKey, Integer> scores = new HashMap<>();

		List<List<VideoCandidate>> groups = SimilaritySingleLinkageGrouper.cluster(candidates, minimum,
				(first, second) -> score(first, second, minimum, scores, buckets), progress);

		List<SimilarVideoGroupResponse> responses = groups.stream()
				.map(group -> toResponse(group, scores, buckets, quality))
				.sorted((first, second) -> Long.compare(second.wastedSize().bytes(), first.wastedSize().bytes()))
				.toList();

		cache.put(minimum, signature, responses);
	}

	void evictFromCache(Collection<UUID> removedPublicIds) {
		if (removedPublicIds == null || removedPublicIds.isEmpty()) {
			return;
		}

		Set<UUID> removed = new HashSet<>(removedPublicIds);

		cache.evict(group -> SimilarityGroupSupport.retains(group.keep().id(), group.deleteCandidates(),
				group.reviewCandidates(), removed));
	}

	public void invalidateCache() {
		cache.invalidate();
	}

	/** Reassembles the per-frame rows (ordered by file then sampleIndex) per video. */
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

	private String fingerprintSignature() {
		return SimilarityGroupSupport
				.signatureOf(mediaFingerprintRepository.fingerprintSignature(algorithm.kind(), algorithm.algorithm()));
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

	private SimilarVideoGroupResponse toResponse(List<VideoCandidate> group, Map<PairKey, Integer> scores,
			Map<UUID, Set<Long>> buckets, Map<UUID, MediaQuality> quality) {
		List<DuplicateFileResponse> files = group.stream().map(this::toFileResponse).toList();

		GroupParts parts = duplicateGroupAssembler.assemble(files, quality, false);

		return new SimilarVideoGroupResponse(String.valueOf(parts.keep().id()), group.size(),
				SimilaritySingleLinkageGrouper.worstScore(group,
						(first, second) -> score(first, second, DuplicateConstants.MIN_SIMILARITY_PERCENT, scores,
								buckets)),
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

	private int maxPageSize() {
		return appSettingService.intValue(SettingsConstants.API_MAX_PAGE_SIZE, properties.api().maxPageSize());
	}
}