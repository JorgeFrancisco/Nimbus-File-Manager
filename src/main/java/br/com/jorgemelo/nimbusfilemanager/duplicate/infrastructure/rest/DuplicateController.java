package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.rest;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.FingerprintFailureLabels;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.SimilarityBounds;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.SimilarityLauncher;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.SimilarityViewService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateCandidateGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateFileResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateSummaryResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogProgress;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintFailureResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.PublishedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.PublishedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFreshness;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityMemberResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityView;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintBacklogProgressReader;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.PhashBacklogService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.VideoFingerprintBacklogService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.SimilarityMemberFile;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PagedResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/duplicates")
public class DuplicateController {

	private final DuplicateService duplicateService;
	private final SimilarityViewService similarityViewService;
	private final SimilarityLauncher similarityLauncher;
	private final PhashBacklogService phashBacklogService;
	private final VideoFingerprintBacklogService videoFingerprintBacklogService;
	private final FingerprintFailureLabels fingerprintFailureLabels;
	private final FingerprintBacklogProgressReader fingerprintBacklogProgressReader;

	public DuplicateController(DuplicateService duplicateService, SimilarityViewService similarityViewService,
			SimilarityLauncher similarityLauncher, PhashBacklogService phashBacklogService,
			VideoFingerprintBacklogService videoFingerprintBacklogService,
			FingerprintFailureLabels fingerprintFailureLabels,
			FingerprintBacklogProgressReader fingerprintBacklogProgressReader) {
		this.duplicateService = duplicateService;
		this.similarityViewService = similarityViewService;
		this.similarityLauncher = similarityLauncher;
		this.phashBacklogService = phashBacklogService;
		this.videoFingerprintBacklogService = videoFingerprintBacklogService;
		this.fingerprintFailureLabels = fingerprintFailureLabels;
		this.fingerprintBacklogProgressReader = fingerprintBacklogProgressReader;
	}

	@GetMapping
	@Operation(summary = "Returns duplicate file groups",
			description = "Lists SHA-256 groups that contain more than one file.")
	public PagedResponse<DuplicateGroupResponse> groups(@PageableDefault(size = 50) Pageable pageable) {
		return PagedResponse.from(duplicateService.groups(pageable, null));
	}

	@GetMapping("/{sha256}/files")
	@Operation(summary = "Returns files from a duplicate group",
			description = "Returns all files that belong to a duplicate SHA-256 group.")
	public List<DuplicateFileResponse> files(@PathVariable String sha256) {
		return duplicateService.files(sha256);
	}

	@GetMapping("/summary")
	@Operation(summary = "Returns duplicate summary")
	public DuplicateSummaryResponse summary() {
		return duplicateService.summary();
	}

	@GetMapping("/candidates")
	@Operation(summary = "Returns duplicate deletion candidates",
			description = "Suggests files that can be removed according to the configured keep strategy.")
	public PagedResponse<DuplicateCandidateGroupResponse> candidates(@PageableDefault(size = 50) Pageable pageable) {
		return PagedResponse.from(duplicateService.candidates(pageable, null));
	}

	/**
	 * The published grouping, or an accepted request for one.
	 *
	 * <p>
	 * It never groups anything itself. Comparing a library is minutes of CPU, and a
	 * request that did it would be a second engine beside the worker - with two
	 * processes free to run it at once, and no way for either to see the other's
	 * answer. So: a published result is returned, and when there is none the
	 * analysis is queued and the answer is 202 with the execution to follow.
	 */
	@GetMapping("/similar-photos")
	@Operation(summary = "Returns groups of visually similar photos",
			description = "Returns the published grouping of PHOTO files by perceptual-hash similarity."
					+ " When no grouping has been published for these parameters yet, the analysis is queued and"
					+ " the response is 202 Accepted with the execution to follow. minSimilarity is clamped to"
					+ " [70, 100], with 70 as the floor and default.")
	public ResponseEntity<PagedResponse<SimilarityGroupResponse>> similarPhotos(
			@RequestParam(required = false) Integer minSimilarity, @PageableDefault(size = 20) Pageable pageable) {
		return published(similarityViewService.photos(SimilarityBounds.clamp(minSimilarity), pageable),
				ExecutionType.SIMILARITY_PHOTO, SimilarityBounds.clamp(minSimilarity),
				() -> similarityLauncher.launchPhotos(SimilarityBounds.clamp(minSimilarity)));
	}

	@GetMapping("/similar-photos/failures")
	@Operation(summary = "Returns exhausted photo-fingerprint failures")
	public List<FingerprintFailureResponse> similarPhotoFailures() {
		return fingerprintFailureLabels.describe(phashBacklogService.failures());
	}

	/** Video counterpart of {@link #similarPhotos}, with the same contract. */
	@GetMapping("/similar-videos")
	@Operation(summary = "Returns groups of visually similar videos",
			description = "Returns the published grouping of VIDEO files by multi-frame perceptual-hash similarity."
					+ " When no grouping has been published for these parameters yet, the analysis is queued and"
					+ " the response is 202 Accepted with the execution to follow. minSimilarity is clamped to"
					+ " [70, 100], with 70 as the floor and default.")
	public ResponseEntity<PagedResponse<SimilarityGroupResponse>> similarVideos(
			@RequestParam(required = false) Integer minSimilarity, @PageableDefault(size = 20) Pageable pageable) {
		return published(similarityViewService.videos(SimilarityBounds.clamp(minSimilarity), pageable),
				ExecutionType.SIMILARITY_VIDEO, SimilarityBounds.clamp(minSimilarity),
				() -> similarityLauncher.launchVideos(SimilarityBounds.clamp(minSimilarity)));
	}

	/**
	 * The published page, or 202 with the analysis that will produce it.
	 *
	 * <p>
	 * An outdated result is still returned rather than withheld: it is a true
	 * statement about the files it examined, and the flag says the library has
	 * moved since. Withholding it would leave a consumer with nothing while a
	 * perfectly usable answer sits in the database.
	 */
	private ResponseEntity<PagedResponse<SimilarityGroupResponse>> published(SimilarityView view, ExecutionType type,
			int minSimilarity, Supplier<Execution> queue) {
		if (!view.published()) {
			Execution execution = queue.get();

			return ResponseEntity.accepted()
					.header("Location", "/api/executions/" + execution.getExecutionPublicId()).build();
		}

		// Asked for here and not carried by the view: the screen renders without it
		// and fetches it afterwards, while this contract promises the flag on every
		// group and so pays for it before answering. One rule, two callers.
		boolean outdated = similarityViewService.outdated(type, minSimilarity).orElse(false);

		return ResponseEntity.ok(PagedResponse.from(view.groups().map(group -> toResponse(group, outdated))));
	}

	private SimilarityGroupResponse toResponse(PublishedGroup group, boolean outdated) {
		return new SimilarityGroupResponse(group.groupId(), group.similarityPercent(), group.wastedBytes(), outdated,
				group.members().stream().map(this::toResponse).toList());
	}

	private SimilarityMemberResponse toResponse(PublishedMember member) {
		SimilarityMemberFile file = member.file();

		return new SimilarityMemberResponse(member.decision().mediaPublicId(), file == null ? null : file.fileName(),
				file == null ? null : file.currentPath(), file == null ? null : file.sizeBytes(),
				member.decision().verdict().name(),
				member.decision().reason() == null ? null : member.decision().reason().name(), member.actionable());
	}

	/**
	 * The backlog behind the similarity comparison, asked for on its own.
	 *
	 * <p>
	 * Separate from the activity poll on purpose - see
	 * {@link FingerprintBacklogProgress} - and separate from this page's HTML,
	 * which the panel used to re-fetch whole every four seconds to learn these
	 * numbers.
	 */
	@GetMapping("/similar-photos/backlog")
	@Operation(summary = "Photo fingerprint backlog: what is done, pending, failed and how long is left")
	public FingerprintBacklogProgress photoBacklog() {
		return fingerprintBacklogProgressReader.forTab(ExecutionType.FINGERPRINT_PHOTO);
	}

	@GetMapping("/similar-videos/backlog")
	@Operation(summary = "Video fingerprint backlog: what is done, pending, failed and how long is left")
	public FingerprintBacklogProgress videoBacklog() {
		return fingerprintBacklogProgressReader.forTab(ExecutionType.FINGERPRINT_VIDEO);
	}

	@GetMapping("/similar-photos/freshness")
	@Operation(summary = "Whether the published photo analysis still describes the library")
	public SimilarityFreshness similarPhotoFreshness(@RequestParam(required = false) Integer minSimilarity) {
		return freshness(ExecutionType.SIMILARITY_PHOTO, SimilarityBounds.clamp(minSimilarity));
	}

	@GetMapping("/similar-videos/freshness")
	@Operation(summary = "Whether the published video analysis still describes the library")
	public SimilarityFreshness similarVideoFreshness(@RequestParam(required = false) Integer minSimilarity) {
		return freshness(ExecutionType.SIMILARITY_VIDEO, SimilarityBounds.clamp(minSimilarity));
	}

	private SimilarityFreshness freshness(ExecutionType type, int minSimilarity) {
		return similarityViewService.outdated(type, minSimilarity)
				.map(outdated -> new SimilarityFreshness(true, outdated))
				.orElseGet(SimilarityFreshness::notPublished);
	}

	@GetMapping("/similar-videos/failures")
	@Operation(summary = "Returns exhausted video-fingerprint failures")
	public List<FingerprintFailureResponse> similarVideoFailures() {
		return fingerprintFailureLabels.describe(videoFingerprintBacklogService.failures());
	}
}