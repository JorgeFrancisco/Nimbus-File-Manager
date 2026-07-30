package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.rest;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.FingerprintFailureLabels;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.PhotoSimilarityService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.VideoSimilarityService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateCandidateGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateFileResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateSummaryResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintFailureResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarPhotoGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarVideoGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.PhashBacklogService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.VideoFingerprintBacklogService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/duplicates")
public class DuplicateController {

	private final DuplicateService duplicateService;
	private final PhotoSimilarityService photoSimilarityService;
	private final PhashBacklogService phashBacklogService;
	private final VideoSimilarityService videoSimilarityService;
	private final VideoFingerprintBacklogService videoFingerprintBacklogService;
	private final FingerprintFailureLabels fingerprintFailureLabels;

	public DuplicateController(DuplicateService duplicateService, PhotoSimilarityService photoSimilarityService,
			PhashBacklogService phashBacklogService, VideoSimilarityService videoSimilarityService,
			VideoFingerprintBacklogService videoFingerprintBacklogService,
			FingerprintFailureLabels fingerprintFailureLabels) {
		this.duplicateService = duplicateService;
		this.photoSimilarityService = photoSimilarityService;
		this.phashBacklogService = phashBacklogService;
		this.videoSimilarityService = videoSimilarityService;
		this.videoFingerprintBacklogService = videoFingerprintBacklogService;
		this.fingerprintFailureLabels = fingerprintFailureLabels;
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

	@GetMapping("/similar-photos")
	@Operation(summary = "Returns groups of visually similar photos",
			description = "Groups PHOTO files by perceptual-hash similarity. minSimilarity is clamped to [70, 100], with 70 as the floor and default.")
	public PagedResponse<SimilarPhotoGroupResponse> similarPhotos(@RequestParam(required = false) Integer minSimilarity,
			@PageableDefault(size = 20) Pageable pageable) {
		return PagedResponse.from(photoSimilarityService.groups(minSimilarity, pageable));
	}

	@GetMapping("/similar-photos/failures")
	@Operation(summary = "Returns exhausted photo-fingerprint failures")
	public List<FingerprintFailureResponse> similarPhotoFailures() {
		return fingerprintFailureLabels.describe(phashBacklogService.failures());
	}

	@GetMapping("/similar-videos")
	@Operation(summary = "Returns groups of visually similar videos",
			description = "Groups VIDEO files by multi-frame perceptual-hash similarity. minSimilarity is clamped to [70, 100], with 70 as the floor and default.")
	public PagedResponse<SimilarVideoGroupResponse> similarVideos(@RequestParam(required = false) Integer minSimilarity,
			@PageableDefault(size = 20) Pageable pageable) {
		return PagedResponse.from(videoSimilarityService.groups(minSimilarity, pageable));
	}

	@GetMapping("/similar-videos/failures")
	@Operation(summary = "Returns exhausted video-fingerprint failures")
	public List<FingerprintFailureResponse> similarVideoFailures() {
		return fingerprintFailureLabels.describe(videoFingerprintBacklogService.failures());
	}
}