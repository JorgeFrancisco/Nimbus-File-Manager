package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateFileResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.GroupParts;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.PairKey;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarPhotoGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityComposition;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFamily;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.CompositionRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.MediaQuality;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRawResponse;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.PhotoPerceptualHashService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PageUtils;

/**
 * Finds visually related photos in two stages: a 256-bit pHash cheaply rejects
 * unrelated pairs, then SSIM confirms candidates and supplies the percentage
 * shown in the UI. A pHash match is never described as an equality or
 * percentage.
 *
 * <p>
 * The class is an engine, not an entry point: nothing here decides when to run.
 * A screen asks {@link SimilarityViewService} what was published, and a run is a
 * row in the queue that a worker takes - so the expensive grouping happens once
 * per definition and survives a restart, instead of once per process.
 */
@Service
@Transactional(readOnly = true)
class PhotoSimilarityService implements SimilarityAnalyzer {

	/** Safety cap while grouping remains an in-memory O(n²) operation. */
	private static final int MAX_CANDIDATES = 8000;

	/**
	 * Generous pHash candidate radius, a wide fraction of the hash. SSIM makes the
	 * final decision, so this stage is intentionally optimized for recall rather
	 * than precision.
	 */
	private static final int MAX_PHASH_CANDIDATE_DISTANCE = 96;

	private final MediaFingerprintRepository mediaFingerprintRepository;
	private final DuplicateGroupAssembler duplicateGroupAssembler;
	private final PhotoSsimService photoSsimService;
	private final DuplicateExclusionService duplicateExclusionService;

	PhotoSimilarityService(MediaFingerprintRepository mediaFingerprintRepository,
			DuplicateGroupAssembler duplicateGroupAssembler, PhotoSsimService photoSsimService,
			DuplicateExclusionService duplicateExclusionService) {
		this.mediaFingerprintRepository = mediaFingerprintRepository;
		this.duplicateGroupAssembler = duplicateGroupAssembler;
		this.photoSsimService = photoSsimService;
		this.duplicateExclusionService = duplicateExclusionService;
	}

	@Override
	public FileType mediaType() {
		return FileType.PHOTO;
	}

	@Override
	public SimilarityFamily family(int minSimilarityPercent) {
		return new SimilarityFamily(FileType.PHOTO, FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1,
				SimilarityConstants.GROUPING_VERSION, parametersDigest(SimilarityBounds.clamp(minSimilarityPercent)));
	}

	/**
	 * Every effective parameter of the photo grouping, in a fixed order. The
	 * candidate cap and the selection policy are in here because they decide
	 * <em>which</em> files are compared, which changes the answer as surely as the
	 * threshold does; the exclusion signature is here because a file the user hid
	 * is a file the analysis may not see.
	 */
	private String parametersDigest(int minimumSsim) {
		return new SimilarityParameters().with("minSimilarity", minimumSsim)
				.with("maxPhashCandidateDistance", MAX_PHASH_CANDIDATE_DISTANCE).with("candidateLimit", MAX_CANDIDATES)
				.with("selectionPolicy", SimilarityConstants.SELECTION_OLDEST_FIRST)
				.with("exclusions", duplicateExclusionService.signature()).digest();
	}

	@Override
	public int eligibleCount() {
		return mediaFingerprintRepository.countEligibleForSimilarity(FingerprintKind.PHOTO_PHASH.name(),
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1);
	}

	@Override
	public int candidateLimit() {
		return MAX_CANDIDATES;
	}

	@Override
	public SimilarityComposition composition() {
		return compositionOf(SimilarityGroupSupport.canonicalComposition(
				mediaFingerprintRepository.findPhotoCompositionRows(FingerprintKind.PHOTO_PHASH,
						FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, PageUtils.firstPage(MAX_CANDIDATES)),
				CompositionRow::mediaPublicId, CompositionRow::currentFolder, duplicateExclusionService));
	}

	private SimilarityComposition compositionOf(List<CompositionRow> rows) {
		return new SimilarityComposition(
				SimilarityDigest.ofComposition(rows.stream().map(CompositionRow::mediaPublicId).toList(),
						rows.stream().map(CompositionRow::currentFolder).toList()),
				eligibleCount(), rows.size(), MAX_CANDIDATES, SimilarityConstants.SELECTION_OLDEST_FIRST);
	}

	/**
	 * The analysis, and the account of what it was about.
	 *
	 * <p>
	 * The order of the two steps is the historical one and is deliberately kept:
	 * the cap is applied by the query, and the exclusions are applied to what it
	 * returned. So a library with many excluded photos analyses fewer than the cap,
	 * which is what it always did. What changes is that the numbers stop being
	 * invisible - the composition says how many were eligible, how many were seen,
	 * and exactly which ones.
	 */
	@Override
	public SimilarityAnalysisResult analyze(int minSimilarityPercent, SimilarityProgressCallback progress) {
		int minimumSsim = SimilarityBounds.clamp(minSimilarityPercent);

		List<PhotoHashRawResponse> rows = mediaFingerprintRepository
				.findFingerprintedPhotos(FingerprintKind.PHOTO_PHASH, FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1,
						PageUtils.firstPage(MAX_CANDIDATES))
				.getContent();

		// The selection happens once, in the primitive the application also calls, and
		// the heavy rows are filtered by what it chose - rather than being selected a
		// second time by an equivalent-looking filter.
		List<CompositionRow> selected = SimilarityGroupSupport.canonicalComposition(rows, PhotoHashRawResponse::id,
				PhotoHashRawResponse::currentFolder, duplicateExclusionService);

		Set<UUID> analysed = selected.stream().map(CompositionRow::mediaPublicId).collect(Collectors.toSet());

		List<PhotoHashRawResponse> candidates = rows.stream().filter(row -> analysed.contains(row.id())).toList();

		List<SimilarPhotoGroupResponse> responses = group(candidates, minimumSsim, progress);

		SimilarityComposition composition = compositionOf(selected);

		List<AnalyzedGroup> groups = responses.stream()
				.map(response -> SimilarityGroupSupport.toAnalyzedGroup(response.similarityPercent(),
						response.wastedSize().bytes(), response.keep(), response.deleteCandidates(),
						response.reviewCandidates()))
				.toList();

		return new SimilarityAnalysisResult(family(minimumSsim), composition, groups);
	}

	/** The clustering itself, over the candidates the selection kept. */
	private List<SimilarPhotoGroupResponse> group(List<PhotoHashRawResponse> candidates, int minimumSsim,
			SimilarityProgressCallback progress) {
		List<UUID> allIds = candidates.stream().map(PhotoHashRawResponse::id).toList();

		Map<UUID, MediaQuality> quality = duplicateGroupAssembler.qualityByPublicId(allIds);

		Map<PairKey, Integer> scores = new HashMap<>();

		List<List<PhotoHashRawResponse>> clusters = SimilarityCompleteLinkageGrouper.cluster(candidates, minimumSsim,
				(first, second) -> score(first, second, scores), progress);

		return clusters.stream().map(group -> toResponse(group, scores, quality))
				.sorted((first, second) -> Long.compare(second.wastedSize().bytes(), first.wastedSize().bytes()))
				.toList();
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
				SimilarityCompleteLinkageGrouper.worstScore(group, (first, second) -> score(first, second, scores)),
				SizeResponse.of(parts.wastedBytes()), parts.keep(), parts.deleteCandidates(), parts.reviewCandidates());
	}

	private DuplicateFileResponse toFileResponse(PhotoHashRawResponse raw) {
		return new DuplicateFileResponse(raw.id(), raw.fileName(), raw.extension(), "PHOTO",
				SizeResponse.of(raw.sizeBytes()), raw.currentPath(), raw.currentFolder(), raw.modifiedAt());
	}

}