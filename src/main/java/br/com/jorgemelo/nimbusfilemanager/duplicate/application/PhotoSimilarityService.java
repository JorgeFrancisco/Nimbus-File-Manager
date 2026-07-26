package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.Collection;
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

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateFileResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.GroupParts;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.PairKey;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarPhotoGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.MediaQuality;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRawResponse;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.PhotoPerceptualHashService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PageUtils;

/**
 * Finds visually related photos in two stages: a 256-bit pHash cheaply rejects
 * unrelated pairs, then SSIM confirms candidates and supplies the percentage
 * shown in the UI. A pHash match is never described as an equality or
 * percentage. The heavy grouping is cached per threshold by the shared
 * {@link SimilarityGroupCache}.
 */
@Service
@Transactional(readOnly = true)
public class PhotoSimilarityService implements SimilarityGrouping {

	/** Safety cap while grouping remains an in-memory O(n²) operation. */
	static final int MAX_CANDIDATES = 8000;

	/**
	 * Generous pHash candidate radius, a wide fraction of the hash. SSIM makes the
	 * final decision, so this stage is intentionally optimized for recall rather
	 * than precision.
	 */
	static final int MAX_PHASH_CANDIDATE_DISTANCE = 96;

	private final MediaFingerprintRepository mediaFingerprintRepository;
	private final DuplicateGroupAssembler duplicateGroupAssembler;
	private final PhotoSsimService photoSsimService;
	private final AppSettingService appSettingService;
	private final NimbusFileManagerProperties properties;
	private final DuplicateExclusionService duplicateExclusionService;

	/**
	 * Caches the heavy grouping (clustering + SSIM) per similarity threshold,
	 * invalidated automatically when the fingerprint set changes, so paginating or
	 * re-opening the Fotos Semelhantes tab is instant instead of recomputing.
	 */
	private final SimilarityGroupCache<SimilarPhotoGroupResponse> cache;

	public PhotoSimilarityService(MediaFingerprintRepository mediaFingerprintRepository,
			DuplicateGroupAssembler duplicateGroupAssembler, PhotoSsimService photoSsimService,
			AppSettingService appSettingService, NimbusFileManagerProperties properties,
			DuplicateExclusionService duplicateExclusionService) {
		this.mediaFingerprintRepository = mediaFingerprintRepository;
		this.duplicateGroupAssembler = duplicateGroupAssembler;
		this.photoSsimService = photoSsimService;
		this.appSettingService = appSettingService;
		this.properties = properties;
		this.duplicateExclusionService = duplicateExclusionService;
		this.cache = new SimilarityGroupCache<>(this::fingerprintSignature, this::maxPageSize);
	}

	/**
	 * Synchronous read used by tests and as a fallback: returns the cached page,
	 * computing (blocking) on a miss. The Duplicados screen does NOT use this - it
	 * uses {@link #cachedPage} plus the background
	 * {@code PhotoSimilarityAsyncRunner} so the page never blocks on the heavy
	 * grouping.
	 */
	public Page<SimilarPhotoGroupResponse> groups(Integer minSimilarityPercent, Pageable pageable) {
		int minimumSsim = SimilarityBounds.clamp(minSimilarityPercent);

		if (!cache.isCached(minimumSsim)) {
			computeAndCache(minimumSsim, (_, _) -> {
			});
		}

		return cache.cachedPage(minimumSsim, pageable).orElseGet(() -> cache.emptyPage(pageable));
	}

	/** Whether the grouping for this threshold is already cached. */
	@Override
	public boolean isCached(int minSimilarityPercent) {
		return cache.isCached(SimilarityBounds.clamp(minSimilarityPercent));
	}

	/**
	 * Page of the cached grouping for this threshold, or empty when it has not been
	 * computed yet - no blocking compute happens here.
	 */
	public Optional<Page<SimilarPhotoGroupResponse>> cachedPage(int minSimilarityPercent, Pageable pageable) {
		return cache.cachedPage(SimilarityBounds.clamp(minSimilarityPercent), pageable);
	}

	/**
	 * Runs the heavy grouping (clustering + SSIM) for a threshold and caches the
	 * result, reporting how many candidates have been processed to
	 * {@code progress}.
	 */
	@Override
	public void computeAndCache(int minSimilarityPercent, SimilarityProgressCallback progress) {
		int minimumSsim = SimilarityBounds.clamp(minSimilarityPercent);

		String signature = cache.currentSignature();

		List<PhotoHashRawResponse> candidates = SimilarityGroupSupport.withoutExcluded(
				mediaFingerprintRepository
						.findFingerprintedPhotos(FingerprintKind.PHOTO_PHASH,
								FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, PageUtils.firstPage(MAX_CANDIDATES))
						.getContent(),
				duplicateExclusionService, PhotoHashRawResponse::id, PhotoHashRawResponse::currentFolder);

		List<UUID> allIds = candidates.stream().map(PhotoHashRawResponse::id).toList();

		Map<UUID, MediaQuality> quality = duplicateGroupAssembler.qualityByPublicId(allIds);

		Map<PairKey, Integer> scores = new HashMap<>();

		List<List<PhotoHashRawResponse>> groups = SimilaritySingleLinkageGrouper.cluster(candidates, minimumSsim,
				(first, second) -> score(first, second, scores), progress);

		List<SimilarPhotoGroupResponse> responses = groups.stream().map(group -> toResponse(group, scores, quality))
				.sorted((first, second) -> Long.compare(second.wastedSize().bytes(), first.wastedSize().bytes()))
				.toList();

		cache.put(minimumSsim, signature, responses);
	}

	/**
	 * Drops the given photos from the cached groupings after a soft-delete, so a
	 * follow-up reload shows the updated groups without recomputing. A group that
	 * loses any member is removed entirely.
	 */
	void evictFromCache(Collection<UUID> removedPublicIds) {
		if (removedPublicIds == null || removedPublicIds.isEmpty()) {
			return;
		}

		Set<UUID> removed = new HashSet<>(removedPublicIds);

		cache.evict(group -> SimilarityGroupSupport.retains(group.keep().id(), group.deleteCandidates(),
				group.reviewCandidates(), removed));
	}

	/**
	 * Clears every cached grouping so the next Fotos Semelhantes load recomputes.
	 * Used when the comparison-exclusion lists change.
	 */
	public void invalidateCache() {
		cache.invalidate();
	}

	private String fingerprintSignature() {
		return SimilarityGroupSupport.signatureOf(mediaFingerprintRepository
				.fingerprintSignature(FingerprintKind.PHOTO_PHASH, FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1));
	}

	private int score(PhotoHashRawResponse first, PhotoHashRawResponse second, Map<PairKey, Integer> scores) {
		if (PhotoPerceptualHashService.distance(first.phash(), second.phash()) > MAX_PHASH_CANDIDATE_DISTANCE) {
			return -1;
		}

		return scores.computeIfAbsent(PairKey.of(first.id(), second.id()),
				_ -> photoSsimService.similarityPercent(first.luminance(), second.luminance()));
	}

	private SimilarPhotoGroupResponse toResponse(List<PhotoHashRawResponse> group, Map<PairKey, Integer> scores,
			Map<UUID, MediaQuality> quality) {
		List<DuplicateFileResponse> files = group.stream().map(this::toFileResponse).toList();

		GroupParts parts = duplicateGroupAssembler.assemble(files, quality, false);

		return new SimilarPhotoGroupResponse(String.valueOf(parts.keep().id()), group.size(),
				SimilaritySingleLinkageGrouper.worstScore(group, (first, second) -> score(first, second, scores)),
				SizeResponse.of(parts.wastedBytes()), parts.keep(), parts.deleteCandidates(), parts.reviewCandidates());
	}

	private DuplicateFileResponse toFileResponse(PhotoHashRawResponse raw) {
		return new DuplicateFileResponse(raw.id(), raw.fileName(), raw.extension(), "PHOTO",
				SizeResponse.of(raw.sizeBytes()), raw.currentPath(), raw.currentFolder(), raw.modifiedAt());
	}

	private int maxPageSize() {
		return appSettingService.intValue(SettingsConstants.API_MAX_PAGE_SIZE, properties.api().maxPageSize());
	}
}