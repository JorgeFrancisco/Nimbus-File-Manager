package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.web;

import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants.MIN_SIMILARITY_KEY;
import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants.MIN_SIMILARITY_VIDEO_KEY;
import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants.TAB_KEY;
import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants.TYPE_FILTER_KEY;
import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants.VIEW_KEY;
import static br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants.PAGE_SIZE_KEY;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateDeletionLauncherService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateDeletionProgressService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateExclusionService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.SimilarityBounds;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.SimilarityLauncher;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.SimilarityViewService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateCandidateFileResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateCandidateGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeleteRequest;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeletionProgress;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeletionResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateExcludeRequest;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateExclusionResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateFileView;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateGroupView;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicatesViewRequest;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.PublishedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.PublishedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityView;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogProgress;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintBacklogProgressReader;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintBacklogLauncher;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.PhashBacklogService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Reason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.SimilarityMemberFile;
import br.com.jorgemelo.nimbusfilemanager.media.domain.enums.MediaTypeFilter;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.DateSourceLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.DateTimeFormatUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.EnumUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.FilePreviewSupport;
import br.com.jorgemelo.nimbusfilemanager.shared.util.FileTypeIcon;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PageUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SecurityUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.enums.Kind;


/**
 * Renders the "Duplicados" screen: exact (byte-identical, SHA-256) duplicate
 * candidates and, as a second tab, visually similar photos. Only one tab's data
 * is loaded per request (whichever the {@code tab} query param selects), so
 * switching tabs is a normal link/GET like the rest of the app (see
 * settings.html's own {@code tab-strip}), not a client-side toggle.
 *
 * <p>
 * Both tabs are mapped into the same {@link DuplicateGroupView}/
 * {@link DuplicateFileView} shape before reaching the template, so
 * {@code duplicates.html} has a single rendering path (table for "details",
 * icon grid for "small"/"large"/"xlarge") shared by both tabs - mirrors the
 * view-mode switch already on the Arquivos explorer ({@code files.html}).
 */
@Controller
public class DuplicatesWebController extends LocalizedComponent {

	private static final List<Integer> PAGE_SIZES = List.of(50, 100, 200);
	private static final int DEFAULT_PAGE_SIZE = 50;
	private static final Set<String> VIEW_MODES = Set.of("details", "small", "large", "xlarge");
	private static final String DEFAULT_TAB = "exact";
	private static final String TAB_SIMILAR = "similar";
	private static final String TAB_VIDEOS = "videos";
	private static final Set<String> TABS = Set.of(DEFAULT_TAB, TAB_SIMILAR, TAB_VIDEOS);
	private static final String PHOTO_ACTION_BASE = "/app/duplicates/phash";
	private static final String VIDEO_ACTION_BASE = "/app/duplicates/phash-video";
	private static final String PHOTOS_TAB = "redirect:/app/duplicates?tab=similar";
	private static final String VIDEOS_TAB = "redirect:/app/duplicates?tab=videos";
	private static final String SYSTEM_USERNAME = "system";
	private static final String ATTR_SIMILARITY_COMPUTING = "similarityComputing";

	private final DuplicateService duplicateService;
	private final PhashBacklogService phashBacklogService;
	private final FingerprintBacklogLauncher fingerprintBacklogLauncher;
	private final UserPagePreferenceService userPagePreferenceService;
	private final SimilarityViewService similarityViewService;
	private final SimilarityLauncher similarityLauncher;
	private final DuplicateDeletionLauncherService duplicateDeletionLauncherService;
	private final DuplicateDeletionProgressService duplicateDeletionProgressService;
	private final DuplicateExclusionService duplicateExclusionService;
	private final VideoSimilarityWeb videoSimilarityWeb;
	private final DateSourceLabels dateSourceLabels;
	private final FingerprintBacklogProgressReader fingerprintBacklogProgressReader;

	@Autowired
	public DuplicatesWebController(DuplicateService duplicateService, PhashBacklogService phashBacklogService,
			FingerprintBacklogLauncher fingerprintBacklogLauncher,
			UserPagePreferenceService userPagePreferenceService,
			SimilarityViewService similarityViewService, SimilarityLauncher similarityLauncher,
			DuplicateDeletionLauncherService duplicateDeletionLauncherService,
			DuplicateDeletionProgressService duplicateDeletionProgressService,
			DuplicateExclusionService duplicateExclusionService, VideoSimilarityWeb videoSimilarityWeb,
			DateSourceLabels dateSourceLabels,
			FingerprintBacklogProgressReader fingerprintBacklogProgressReader) {
		this.duplicateService = duplicateService;
		this.phashBacklogService = phashBacklogService;
		this.fingerprintBacklogLauncher = fingerprintBacklogLauncher;
		this.userPagePreferenceService = userPagePreferenceService;
		this.similarityViewService = similarityViewService;
		this.similarityLauncher = similarityLauncher;
		this.duplicateDeletionLauncherService = duplicateDeletionLauncherService;
		this.duplicateDeletionProgressService = duplicateDeletionProgressService;
		this.duplicateExclusionService = duplicateExclusionService;
		this.videoSimilarityWeb = videoSimilarityWeb;
		this.dateSourceLabels = dateSourceLabels;
		this.fingerprintBacklogProgressReader = fingerprintBacklogProgressReader;
	}

	@GetMapping("/app/duplicates")
	public String duplicates(@ModelAttribute DuplicatesViewRequest request, Authentication authentication,
			Model model) {
		String safeTab = resolveTab(request.tab(), authentication);

		boolean similarTab = TAB_SIMILAR.equals(safeTab);

		boolean videosTab = TAB_VIDEOS.equals(safeTab);

		int pageSize = resolvePageSize(request.size(), authentication);

		int safeMinSimilarity = resolveMinSimilarity(request.minSimilarity(), authentication, videosTab);

		String safeView = resolveView(request.view(), authentication);

		int page = request.page() == null ? 0 : request.page();

		model.addAttribute("activeTab", safeTab);

		// The failures dialog is one piece of markup shared by the tabs, so which list
		// it loads is decided here. It used to ask for photos whatever the tab was, and
		// the Videos tab answered with photo files.
		model.addAttribute("failuresUrl",
				videosTab ? "/api/duplicates/similar-videos/failures" : "/api/duplicates/similar-photos/failures");

		// Asked after the page is on screen, not before it is built. Identifying the
		// whole eligible library to compare one digest was measured at 2,5 s of the
		// 2,9 s this navigation cost, and nothing rendered here depends on the answer.
		model.addAttribute("backlogUrl",
				videosTab ? "/api/duplicates/similar-videos/backlog" : "/api/duplicates/similar-photos/backlog");
		model.addAttribute("similarityFreshnessUrl",
				(videosTab ? "/api/duplicates/similar-videos/freshness" : "/api/duplicates/similar-photos/freshness")
						+ "?minSimilarity=" + safeMinSimilarity);
		model.addAttribute(MIN_SIMILARITY_KEY, safeMinSimilarity);
		model.addAttribute("minSimilarityFloor", DuplicateConstants.MIN_SIMILARITY_PERCENT);
		model.addAttribute("similarityOptions", List.of(70, 75, 80, 85, 90, 95, 100));

		Set<MediaTypeFilter> typeFilter = resolveTypeFilter(request.types(), authentication);

		model.addAttribute("view", safeView);
		model.addAttribute("pageSizes", PAGE_SIZES);
		model.addAttribute(PAGE_SIZE_KEY, pageSize);
		model.addAttribute("typeFilterOptions", MediaTypeFilter.values());
		model.addAttribute("selectedTypeFilters",
				typeFilter.stream().map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new)));

		// A running inventory means a file arriving right now may not be catalogued
		// yet, so a group it belongs to can be missing from the results. That is the
		// normal state of a library that keeps receiving photos, not a reason to take
		// the screen away: what is already analysed is analysed, and the user still
		// chooses which files to delete. The screen says an inventory is running so
		// the incompleteness is visible rather than silent.
		model.addAttribute("inventoryActive", phashBacklogService.inventoryActive());

		// A conversion does not make the analysis wrong - it only holds the quarantine
		// a deletion writes to. So the results stay on screen and only the deletion is
		// refused, with the reason said up front instead of after the click. It
		// arrives localized: the screen only shows what it is given.
		model.addAttribute("deletionBlockedMessage",
				phashBacklogService.conversionActive() ? message("backend.duplicates.deletionBlockedByConversion")
						: null);

		if (videosTab) {
			renderVideoTab(model, safeMinSimilarity, page, pageSize);
		} else if (similarTab) {
			renderPhotoTab(model, safeMinSimilarity, page, pageSize);
		} else {
			FingerprintBacklogProgress progress = fingerprintBacklogProgressReader
					.forTab(ExecutionType.FINGERPRINT_PHOTO);

			setBacklogAttributes(model, progress, PHOTO_ACTION_BASE, false);

			// The exact tab has no analysis of its own, but the template is one page: the
			// similarity attributes get their neutral values so no expression on it is ever
			// evaluated against a missing one.
			renderSimilarity(model, SimilarityView.none(), progress, false);

			Page<DuplicateCandidateGroupResponse> exactPage = duplicateService
					.candidates(PageRequest.of(page, pageSize), MediaTypeFilter.fileTypesOf(typeFilter));

			addPageAttributes(model, exactPage, exactPage.getContent().stream().map(this::toView).toList());
		}

		return "app/duplicates";
	}

	/**
	 * Manual retry of exhausted fingerprint failures: clears them so they return to
	 * the pending queue and re-kicks the backlog. Bounded auto-retries then apply
	 * again.
	 */
	@PostMapping("/app/duplicates/phash/retry")
	public String retryFingerprints() {
		phashBacklogService.resetFailures();

		fingerprintBacklogLauncher.launch(ExecutionType.FINGERPRINT_PHOTO, false);

		return PHOTOS_TAB;
	}

	/** Rebuilds only visual fingerprints and SSIM samples; no inventory runs. */
	@PostMapping("/app/duplicates/phash/rebuild")
	public String rebuildFingerprints() {
		fingerprintBacklogLauncher.launch(ExecutionType.FINGERPRINT_PHOTO, true);

		return PHOTOS_TAB;
	}

	/** Video counterpart of {@link #retryFingerprints()}. */
	@PostMapping("/app/duplicates/phash-video/retry")
	public String retryVideoFingerprints() {
		videoSimilarityWeb.backlogService().resetFailures();

		fingerprintBacklogLauncher.launch(ExecutionType.FINGERPRINT_VIDEO, false);

		return VIDEOS_TAB;
	}

	/**
	 * Asks for a new similarity analysis of the photos.
	 *
	 * <p>
	 * Queued and never awaited: grouping a library takes minutes, the previous
	 * answer stays on screen while it runs, and an equivalent request already in
	 * the queue is reused rather than duplicated. The published result is replaced
	 * only when the new one is published, atomically.
	 */
	@PostMapping("/app/duplicates/phash/analyze")
	public String analyzePhotos(@RequestParam(required = false) Integer minSimilarity,
			Authentication authentication) {
		similarityLauncher.launchPhotos(resolveMinSimilarity(minSimilarity, authentication, false));

		return PHOTOS_TAB;
	}

	/** Video counterpart of {@link #analyzePhotos}. */
	@PostMapping("/app/duplicates/phash-video/analyze")
	public String analyzeVideos(@RequestParam(required = false) Integer minSimilarity,
			Authentication authentication) {
		similarityLauncher.launchVideos(resolveMinSimilarity(minSimilarity, authentication, true));

		return VIDEOS_TAB;
	}

	/** Video counterpart of {@link #rebuildFingerprints()}. */
	@PostMapping("/app/duplicates/phash-video/rebuild")
	public String rebuildVideoFingerprints() {
		fingerprintBacklogLauncher.launch(ExecutionType.FINGERPRINT_VIDEO, true);

		return VIDEOS_TAB;
	}

	/**
	 * Soft-deletes the selected duplicate files: moves each to the configured
	 * quarantine folder as an undoable {@code DEDUP_DELETE} execution (see
	 * the worker). The move runs in the background so the
	 * screen is never blocked; this only queues it and returns the initial
	 * progress snapshot, then the screen polls {@link #deleteProgress()} for
	 * "Movendo X de N" until it finishes and the final
	 * {@link DuplicateDeletionResult} arrives.
	 */
	@PostMapping("/app/duplicates/delete")
	@ResponseBody
	public DuplicateDeletionProgress delete(@RequestBody DuplicateDeleteRequest request) {
		List<UUID> ids = request == null || request.ids() == null ? List.of() : request.ids();

		// Queued, never started here: the moving happens in the worker, and what comes
		// back is the first snapshot of a request that is now waiting.
		if (!ids.isEmpty()) {
			duplicateDeletionLauncherService.launch(ids);
		}

		return deleteProgress();
	}

	/**
	 * Hides a single file from duplicate comparison (both the exact and the similar
	 * tab) without touching the file itself: it stays inventoried and visible
	 * everywhere else. The exact tab reflects it on the next reload because its
	 * queries filter the exclusion tables directly; the similar tab needs its
	 * cached grouping cleared so the excluded photo drops out on recompute.
	 */
	@PostMapping("/app/duplicates/exclude/file")
	@ResponseBody
	public DuplicateExclusionResponse excludeFile(@RequestBody DuplicateExcludeRequest request) {
		return new DuplicateExclusionResponse(
				duplicateExclusionService.excludeFile(request == null ? null : request.publicId()));
	}

	/**
	 * Hides a whole folder (recursively) from duplicate comparison. Same semantics
	 * as {@link #excludeFile(DuplicateExcludeRequest)}: nothing is deleted, both
	 * tabs stop comparing every current and future file at or under the folder.
	 */
	@PostMapping("/app/duplicates/exclude/folder")
	@ResponseBody
	public DuplicateExclusionResponse excludeFolder(@RequestBody DuplicateExcludeRequest request) {
		return new DuplicateExclusionResponse(
				duplicateExclusionService.excludeFolder(request == null ? null : request.folder()));
	}

	/**
	 * Current snapshot of the background deletion, polled by the screen to drive
	 * the progress bar.
	 */
	@GetMapping("/app/duplicates/delete/progress")
	@ResponseBody
	public DuplicateDeletionProgress deleteProgress() {
		return duplicateDeletionProgressService.snapshot();
	}

	/**
	 * Renders the Fotos Semelhantes tab without ever blocking on the heavy
	 * grouping: if the result is cached it shows the groups; otherwise it kicks off
	 * the background runner and shows a "Calculando semelhança…" panel that polls
	 * until the compute finishes.
	 */
	private void renderPhotoTab(Model model, int safeMinSimilarity, int page, int pageSize) {
		FingerprintBacklogProgress progress = fingerprintBacklogProgressReader
				.forTab(ExecutionType.FINGERPRINT_PHOTO);

		boolean block = progress.pending() > 0;

		setBacklogAttributes(model, progress, PHOTO_ACTION_BASE, block);

		SimilarityView view = similarityViewService.photos(safeMinSimilarity, PageRequest.of(page, pageSize));

		renderSimilarity(model, view, progress, false);

	}

	private void renderVideoTab(Model model, int safeMinSimilarity, int page, int pageSize) {
		FingerprintBacklogProgress progress = fingerprintBacklogProgressReader
				.forTab(ExecutionType.FINGERPRINT_VIDEO);

		boolean block = progress.pending() > 0;

		setBacklogAttributes(model, progress, VIDEO_ACTION_BASE, block);

		renderSimilarity(model, similarityViewService.videos(safeMinSimilarity, PageRequest.of(page, pageSize)),
				progress, true);
	}

	/**
	 * The published analysis, and what is true about it now.
	 *
	 * <p>
	 * Nothing is computed here and nothing is started: the screen reads what a
	 * worker published. A result stays on screen while a newer analysis is being
	 * computed, while the library moves underneath it, and after a failed
	 * recalculation - the only thing that replaces it is a successful publication.
	 */
	private void renderSimilarity(Model model, SimilarityView view, FingerprintBacklogProgress progress,
			boolean videos) {
		model.addAttribute(ATTR_SIMILARITY_COMPUTING, view.analyzing());
		model.addAttribute("similarityPublished", view.published());
		model.addAttribute("similarityEligible", view.eligibleCount());
		model.addAttribute("similarityAnalyzed", view.analyzedCount());
		model.addAttribute("similarityCandidateLimit", view.candidateLimit());
		model.addAttribute("similarityCoverageComplete", view.coverageComplete());

		// Two different statements, and the screen needs both before it may deliver a
		// verdict. The analysis can have covered every file it was able to compare -
		// which is all coverageComplete claims - while most of the library still has
		// no fingerprint to compare with. Saying "no similar photos found" then is a
		// claim about the library made from a fraction of it.
		//
		// A file whose fingerprint failed for good counts the same way: it will never
		// be compared, so the library was never fully examined.
		model.addAttribute("similarityLibraryComplete", progress.pending() == 0 && progress.failed() == 0);
		model.addAttribute("similarityAnalyzeAction",
				videos ? VIDEO_ACTION_BASE + "/analyze" : PHOTO_ACTION_BASE + "/analyze");

		if (progress.pending() > 0) {
			// Fingerprints are still being computed: what exists to group is not yet what
			// the user is waiting for, so no partial answer is offered.
			addPageAttributes(model, Page.empty(), List.of());

			return;
		}

		addPageAttributes(model, view.groups(),
				view.groups().getContent().stream().map(group -> toView(group, videos)).toList());
	}

	/**
	 * The first render of the panel the poll then keeps current - both built from
	 * the same reader, so the page and the answer that replaces it can never say
	 * different things.
	 */
	private void setBacklogAttributes(Model model, FingerprintBacklogProgress progress, String actionBase,
			boolean blocking) {
		model.addAttribute("phashPending", progress.pending());
		model.addAttribute("phashDone", progress.done());
		model.addAttribute("phashFailed", progress.failed());
		model.addAttribute("phashTotal", progress.total());
		model.addAttribute("phashPercent", progress.percent());
		model.addAttribute("phashEtaLabel", progress.etaLabel());
		model.addAttribute("phashBlocking", blocking);
		model.addAttribute("phashRunning", progress.running());
		model.addAttribute("phashRebuildAvailable", !progress.running());
		model.addAttribute("phashOther", progress.other());
		model.addAttribute("retryAction", actionBase + "/retry");
		model.addAttribute("rebuildAction", actionBase + "/rebuild");
	}

	private void addPageAttributes(Model model, Page<?> page, List<DuplicateGroupView> groups) {
		model.addAttribute("groups", groups);
		model.addAttribute("pageNumber", page.getNumber());
		model.addAttribute("hasPrevious", page.getNumber() > 0);
		model.addAttribute("hasNext", page.hasNext());
		model.addAttribute("totalElements", page.getTotalElements());
		model.addAttribute("totalPages", page.getTotalPages());
	}

	private DuplicateGroupView toView(DuplicateCandidateGroupResponse group) {
		String header = group.files() == 1 ? message("backend.duplicates.exactGroup.one", group.files())
				: message("backend.duplicates.exactGroup.many", group.files());

		String badge = message("backend.duplicates.recoverable", group.wastedSize().formatted());

		return new DuplicateGroupView(group.sha256(), header, badge,
				toFileViews(group.keep(), group.deleteCandidates(), group.reviewCandidates()));
	}

	/**
	 * A published group, rendered from the durable result.
	 *
	 * <p>
	 * The header counts what the analysis found; the cards render the members whose
	 * catalog row still exists. A member deleted or quarantined since keeps its
	 * place in the count - the analysis was not wrong - and one whose row is gone
	 * for good has nothing left to draw, so it is counted and not drawn. Neither
	 * case rewrites the published result.
	 */
	private DuplicateGroupView toView(PublishedGroup group, boolean videos) {
		int files = group.members().size();

		String header = headerOf(files, videos);

		String badge = message(videos ? "backend.duplicates.similarVideoBadge" : "backend.duplicates.similarBadge",
				group.similarityPercent(), SizeResponse.of(group.wastedBytes()).formatted());

		List<DuplicateFileView> cards = group.members().stream().filter(member -> member.file() != null)
				.map(this::toFileView).toList();

		return new DuplicateGroupView(group.groupId(), header, badge, cards);
	}

	private String headerOf(int files, boolean videos) {
		if (videos) {
			return files == 1 ? message("backend.duplicates.similarVideoGroup.one", files)
					: message("backend.duplicates.similarVideoGroup.many", files);
		}

		return files == 1 ? message("backend.duplicates.similarGroup.one", files)
				: message("backend.duplicates.similarGroup.many", files);
	}

	/**
	 * The verdict decides the two flags, as it did when the groups were assembled
	 * in memory: the kept file is the recommendation, a review candidate is kept
	 * without being recommended, and a deletion candidate is neither.
	 */
	private DuplicateFileView toFileView(PublishedMember member) {
		SimilarityMemberFile file = member.file();

		Verdict verdict = member.decision().verdict();

		DuplicateCandidateFileResponse response = new DuplicateCandidateFileResponse(file.publicId(), file.fileName(),
				file.extension(), file.fileType(), SizeResponse.of(file.sizeBytes()), file.currentPath(),
				file.currentFolder(), file.modifiedAt(), verdict, member.decision().reason(), file.width(),
				file.height(), file.captureDate(), file.dateSource());

		return toFileView(response, verdict != Verdict.DELETE_CANDIDATE, verdict == Verdict.KEEP,
				member.actionable());
	}

	/**
	 * Maps files into Views with their cached decisions (verdict/reason) directly
	 * from the DTO.
	 */
	private List<DuplicateFileView> toFileViews(DuplicateCandidateFileResponse keep,
			List<DuplicateCandidateFileResponse> deleteCandidates,
			List<DuplicateCandidateFileResponse> reviewCandidates) {
		List<DuplicateCandidateFileResponse> files = new ArrayList<>();

		files.add(keep);
		files.addAll(deleteCandidates);
		files.addAll(reviewCandidates);

		List<DuplicateFileView> views = new ArrayList<>(files.size());

		views.add(toFileView(keep, true, true));

		deleteCandidates.forEach(file -> views.add(toFileView(file, false, false)));
		reviewCandidates.forEach(file -> views.add(toFileView(file, true, false)));

		return List.copyOf(views);
	}

	/**
	 * Resolves the page size from the request or the user's saved preference
	 * (Duplicados page/key), clamped to the allowed set; persists it when a valid
	 * value comes in the request. Because the size is stored per user, the other
	 * links on the screen can omit it and still keep the chosen size.
	 */
	private int resolvePageSize(Integer requested, Authentication authentication) {
		String username = SecurityUtils.usernameOr(authentication, SYSTEM_USERNAME);

		if (requested != null && PAGE_SIZES.contains(requested)) {
			userPagePreferenceService.save(username, DuplicateConstants.PAGE_KEY, PAGE_SIZE_KEY, requested.toString());

			return requested;
		}

		return PageUtils.validSizeOrDefault(
				userPagePreferenceService.find(username, DuplicateConstants.PAGE_KEY).get(PAGE_SIZE_KEY), PAGE_SIZES,
				DEFAULT_PAGE_SIZE);
	}

	/**
	 * Resolves the minimum-similarity threshold from the request or the user's
	 * saved preference, clamped to the allowed range, persisting it when it comes
	 * in the request. Persisting matters for safety: the value must never silently
	 * fall back to the floor on a link that omits it, because a looser threshold
	 * would surface (and could delete) less-similar photos than the user intended.
	 */
	private int resolveMinSimilarity(Integer requested, Authentication authentication, boolean videosTab) {
		String username = SecurityUtils.usernameOr(authentication, SYSTEM_USERNAME);

		// Photos and videos keep independent thresholds - each tab reads and persists
		// its own preference key.
		String key = videosTab ? MIN_SIMILARITY_VIDEO_KEY : MIN_SIMILARITY_KEY;

		if (requested != null) {
			int clamped = SimilarityBounds.clamp(requested);

			userPagePreferenceService.save(username, DuplicateConstants.PAGE_KEY, key, String.valueOf(clamped));

			return clamped;
		}

		String saved = userPagePreferenceService.find(username, DuplicateConstants.PAGE_KEY).get(key);

		if (saved != null && !saved.isBlank()) {
			try {
				return SimilarityBounds.clamp(Integer.parseInt(saved.trim()));
			} catch (NumberFormatException _) {
				// fall through to the floor
			}
		}

		return DuplicateConstants.MIN_SIMILARITY_PERCENT;
	}

	/**
	 * Resolves the view mode from the request or the user's saved preference,
	 * persisting it when a valid value comes in the request; falls back to
	 * "details". Like the other Duplicados options, the view is remembered per user
	 * so navigating/paginating/switching tabs doesn't reset it.
	 */
	private String resolveView(String requested, Authentication authentication) {
		String username = SecurityUtils.usernameOr(authentication, SYSTEM_USERNAME);

		if (requested != null && VIEW_MODES.contains(requested)) {
			userPagePreferenceService.save(username, DuplicateConstants.PAGE_KEY, VIEW_KEY, requested);

			return requested;
		}

		String saved = userPagePreferenceService.find(username, DuplicateConstants.PAGE_KEY).get(VIEW_KEY);

		return saved != null && VIEW_MODES.contains(saved) ? saved : "details";
	}

	/**
	 * Resolves the media-type filter from the request checkboxes or the user's
	 * saved preference, persisting it when the filter form submits (any {@code
	 * types} present). An empty selection widens to every group, so the other links
	 * can omit it and still keep the chosen filter - like the view/size options.
	 */
	private Set<MediaTypeFilter> resolveTypeFilter(List<MediaTypeFilter> requested, Authentication authentication) {
		String username = SecurityUtils.usernameOr(authentication, SYSTEM_USERNAME);

		if (requested != null) {
			Set<MediaTypeFilter> selected = requested.isEmpty() ? EnumSet.allOf(MediaTypeFilter.class)
					: EnumSet.copyOf(requested);

			userPagePreferenceService.save(username, DuplicateConstants.PAGE_KEY, TYPE_FILTER_KEY,
					selected.stream().map(Enum::name).collect(Collectors.joining(",")));

			return selected;
		}

		return parseTypeFilter(
				userPagePreferenceService.find(username, DuplicateConstants.PAGE_KEY).get(TYPE_FILTER_KEY));
	}

	private Set<MediaTypeFilter> parseTypeFilter(String csv) {
		if (csv == null || csv.isBlank()) {
			return EnumSet.allOf(MediaTypeFilter.class);
		}

		Set<MediaTypeFilter> parsed = Arrays.stream(csv.split(",")).map(String::trim).filter(value -> !value.isEmpty())
				.map(name -> EnumUtils.valueOfOrNull(MediaTypeFilter.class, name)).filter(Objects::nonNull)
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(MediaTypeFilter.class)));

		return parsed.isEmpty() ? EnumSet.allOf(MediaTypeFilter.class) : parsed;
	}

	/**
	 * Resolves the active tab from the request or the user's saved preference,
	 * persisting it when a valid value comes in the request; falls back to "exact".
	 * Like the other Duplicados options, the tab is remembered per user, so
	 * returning to the screen (e.g. from the menu) reopens the last tab.
	 */
	private String resolveTab(String requested, Authentication authentication) {
		String username = SecurityUtils.usernameOr(authentication, SYSTEM_USERNAME);

		if (requested != null && TABS.contains(requested)) {
			userPagePreferenceService.save(username, DuplicateConstants.PAGE_KEY, TAB_KEY, requested);

			return requested;
		}

		String saved = userPagePreferenceService.find(username, DuplicateConstants.PAGE_KEY).get(TAB_KEY);

		return saved != null && TABS.contains(saved) ? saved : DEFAULT_TAB;
	}

	private String reasonText(Verdict verdict, Reason reason) {
		if (verdict == null || reason == null) {
			return null;
		}

		return switch (reason) {
		case ORIGINAL -> message("backend.duplicates.reason.original");
		case BEST_IN_GROUP -> message("backend.duplicates.reason.best");
		case WHATSAPP_COPY -> message("backend.duplicates.reason.whatsapp");
		case EDITED_COPY -> message("backend.duplicates.reason.edited");
		case DERIVATIVE -> message("backend.duplicates.reason.derivative");
		case IDENTICAL_COPY -> message("backend.duplicates.reason.identical");
		case REVIEW_NO_CLEAR_ORIGINAL -> message("backend.duplicates.reason.review");
		};
	}

	private DuplicateFileView toFileView(DuplicateCandidateFileResponse file, boolean keep, boolean recommendedKeep) {
		return toFileView(file, keep, recommendedKeep, true);
	}

	private DuplicateFileView toFileView(DuplicateCandidateFileResponse file, boolean keep, boolean recommendedKeep,
			boolean actionable) {
		Kind previewKind = FilePreviewSupport.kind(file.fileType(), file.extension());

		boolean image = previewKind == Kind.IMAGE;
		boolean video = previewKind == Kind.VIDEO;
		boolean pdf = previewKind == Kind.PDF;
		boolean text = previewKind == Kind.TEXT;
		boolean audio = previewKind == Kind.AUDIO;

		String previewUrl = image || video ? thumbnailUrl(file.id()) : null;
		String contentUrl = previewKind == Kind.NONE ? null : contentUrl(file.id());
		boolean previewable = image || video || pdf || text || audio;

		String resolution = file.width() == null || file.height() == null ? null : file.width() + " × " + file.height();
		String highlight = file.reason() == null ? null : file.reason().name();
		String highlightLabel = highlightLabel(file.reason());
		String reason = reasonText(file.verdict(), file.reason());

		return new DuplicateFileView(file.id(), file.fileName(), file.currentFolder(), file.currentPath(), file.size(),
				file.modifiedAt(), file.captureDate(), keep, recommendedKeep, image, video, pdf, text, audio,
				previewUrl, contentUrl, FileTypeIcon.iconClass(file.fileType()), localizedIconLabel(file.fileType()),
				highlight, highlightLabel, reason, resolution, previewable, lightboxClass(pdf, text, audio),
				openTitle(pdf, text, audio), dateSourceLabels.label(file.dateSource()),
				dateSourceBadgeClass(file.dateSource()), DateTimeFormatUtils.human(file.captureDate()),
				DateTimeFormatUtils.human(file.modifiedAt()), actionable);
	}

	/**
	 * Localized badge text for a recommendation reason, resolved in the backend so
	 * the template never maps the {@code Reason} enum to text itself. A null reason
	 * (no clear recommendation) reads as "review", mirroring the badge tier.
	 */
	private String highlightLabel(Reason reason) {
		if (reason == null) {
			return message("duplicates.highlight.review");
		}

		return switch (reason) {
		case ORIGINAL -> message("duplicates.highlight.original");
		case BEST_IN_GROUP -> message("duplicates.highlight.keep");
		case WHATSAPP_COPY -> message("duplicates.highlight.whatsapp");
		case EDITED_COPY -> message("duplicates.highlight.edited");
		case DERIVATIVE -> message("duplicates.highlight.derivative");
		case IDENTICAL_COPY -> message("duplicates.highlight.copy");
		case REVIEW_NO_CLEAR_ORIGINAL -> message("duplicates.highlight.review");
		};
	}

	private String lightboxClass(boolean pdf, boolean text, boolean audio) {
		if (pdf) {
			return "js-lightbox-pdf";
		}

		if (text) {
			return "js-lightbox-text";
		}

		if (audio) {
			return "js-lightbox-audio";
		}

		return "";
	}

	/**
	 * Resolves the icon tooltip against the message bundles. Audio and video keep
	 * their accented pt-BR wording ({@code backend.file.*}) on this screen; every
	 * other type resolves its shared {@code filetype.*} key directly.
	 */
	private String localizedIconLabel(String fileType) {
		String key = FileTypeIcon.iconLabelKey(fileType);

		return switch (key) {
		case "filetype.audio" -> message("backend.file.audio");
		case "filetype.video" -> message("backend.file.video");
		default -> message(key);
		};
	}

	private String openTitle(boolean pdf, boolean text, boolean audio) {
		if (pdf) {
			return message("backend.file.openPdf");
		}

		if (text) {
			return message("backend.file.openText");
		}

		if (audio) {
			return message("backend.file.playAudio");
		}

		return message("backend.file.open");
	}

	/**
	 * Badge style by trust tier: embedded = ok, name/folder = info, filesystem =
	 * muted, none = warn.
	 */
	private String dateSourceBadgeClass(DateSource source) {
		if (source == null) {
			return "muted";
		}

		return switch (source) {
		case EXIF, MEDIA_INFO -> "ok";
		case FILE_NAME_CONFIRMED, FILE_NAME, FOLDER_LAYOUT -> "info";
		case FILE_MODIFIED_AT, FILE_CREATED_AT -> "muted";
		case UNKNOWN -> "warn";
		};
	}

	/**
	 * Uses the cached thumbnail endpoint instead of streaming the original file.
	 * Duplicate groups can contain many high-resolution photos, and decoding all
	 * originals when the user selects a large icon view can otherwise stall the
	 * browser.
	 */
	private String thumbnailUrl(UUID publicId) {
		return "/api/media/" + publicId + "/thumbnail?w=320";
	}

	private String contentUrl(UUID publicId) {
		return "/api/media/" + publicId + "/content";
	}
}