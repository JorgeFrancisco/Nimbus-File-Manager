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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateDeletionAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateExclusionService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.PhotoSimilarityAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.PhotoSimilarityService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.SimilarityBounds;
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
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarPhotoGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarVideoGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.PhashBacklogAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.PhashBacklogService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Reason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;
import br.com.jorgemelo.nimbusfilemanager.media.domain.enums.MediaTypeFilter;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
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
	private static final String SYSTEM_USERNAME = "system";
	private static final String ATTR_SIMILARITY_COMPUTING = "similarityComputing";

	private final DuplicateService duplicateService;
	private final PhotoSimilarityService photoSimilarityService;
	private final PhashBacklogService phashBacklogService;
	private final PhashBacklogAsyncRunner phashBacklogAsyncRunner;
	private final UserPagePreferenceService userPagePreferenceService;
	private final PhotoSimilarityAsyncRunner photoSimilarityAsyncRunner;
	private final DuplicateDeletionAsyncRunner duplicateDeletionAsyncRunner;
	private final DuplicateExclusionService duplicateExclusionService;
	private final VideoSimilarityWeb videoSimilarityWeb;

	@Autowired
	public DuplicatesWebController(DuplicateService duplicateService, PhotoSimilarityService photoSimilarityService,
			PhashBacklogService phashBacklogService, PhashBacklogAsyncRunner phashBacklogAsyncRunner,
			UserPagePreferenceService userPagePreferenceService, PhotoSimilarityAsyncRunner photoSimilarityAsyncRunner,
			DuplicateDeletionAsyncRunner duplicateDeletionAsyncRunner,
			DuplicateExclusionService duplicateExclusionService, VideoSimilarityWeb videoSimilarityWeb) {
		this.duplicateService = duplicateService;
		this.photoSimilarityService = photoSimilarityService;
		this.phashBacklogService = phashBacklogService;
		this.phashBacklogAsyncRunner = phashBacklogAsyncRunner;
		this.userPagePreferenceService = userPagePreferenceService;
		this.photoSimilarityAsyncRunner = photoSimilarityAsyncRunner;
		this.duplicateDeletionAsyncRunner = duplicateDeletionAsyncRunner;
		this.duplicateExclusionService = duplicateExclusionService;
		this.videoSimilarityWeb = videoSimilarityWeb;
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

		// A running inventory makes BOTH tabs' results partial (a file's duplicate may
		// not
		// be cataloged yet). Since this screen previews - and soon executes -
		// deletions,
		// that could delete the wrong file, so the whole screen is blocked until it
		// ends.
		boolean inventoryActive = phashBacklogService.inventoryActive();

		model.addAttribute("inventoryActive", inventoryActive);

		// A conversion does not make the analysis wrong - it only holds the quarantine
		// a deletion writes to. So the results stay on screen and only the deletion is
		// refused, with the reason said up front instead of after the click. It
		// arrives localized: the screen only shows what it is given.
		model.addAttribute("deletionBlockedMessage", phashBacklogService.conversionActive()
				? message("backend.duplicates.deletionBlockedByConversion")
				: null);

		if (inventoryActive) {
			addPageAttributes(model, Page.empty(), List.of());

			return "app/duplicates";
		}

		if (videosTab) {
			renderVideoTab(model, safeMinSimilarity, page, pageSize);
		} else if (similarTab) {
			renderPhotoTab(model, safeMinSimilarity, page, pageSize);
		} else {
			setBacklogAttributes(model, phashBacklogService.status(), phashBacklogAsyncRunner.etaSeconds(),
					phashBacklogAsyncRunner.isRunning(), PHOTO_ACTION_BASE, false);
			model.addAttribute(ATTR_SIMILARITY_COMPUTING, false);

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

		if (phashBacklogAsyncRunner.start()) {
			phashBacklogAsyncRunner.run();
		}

		return "redirect:/app/duplicates?tab=similar";
	}

	/** Rebuilds only visual fingerprints and SSIM samples; no inventory runs. */
	@PostMapping("/app/duplicates/phash/rebuild")
	public String rebuildFingerprints() {
		if (phashBacklogAsyncRunner.prepareRebuild()) {
			phashBacklogAsyncRunner.run();
		}

		return "redirect:/app/duplicates?tab=similar";
	}

	/** Video counterpart of {@link #retryFingerprints()}. */
	@PostMapping("/app/duplicates/phash-video/retry")
	public String retryVideoFingerprints() {
		videoSimilarityWeb.backlogService().resetFailures();

		if (videoSimilarityWeb.backlogRunner().start()) {
			videoSimilarityWeb.backlogRunner().run();
		}

		return "redirect:/app/duplicates?tab=videos";
	}

	/** Video counterpart of {@link #rebuildFingerprints()}. */
	@PostMapping("/app/duplicates/phash-video/rebuild")
	public String rebuildVideoFingerprints() {
		if (videoSimilarityWeb.backlogRunner().prepareRebuild()) {
			videoSimilarityWeb.backlogRunner().run();
		}

		return "redirect:/app/duplicates?tab=videos";
	}

	/**
	 * Soft-deletes the selected duplicate files: moves each to the configured
	 * quarantine folder as an undoable {@code DEDUP_DELETE} execution (see
	 * {@link DuplicateDeletionAsyncRunner}). The move runs in the background so the
	 * screen is never blocked; this only kicks it off and returns the initial
	 * progress snapshot, then the screen polls {@link #deleteProgress()} for
	 * "Movendo X de N" until it finishes and the final
	 * {@link DuplicateDeletionResult} arrives.
	 */
	@PostMapping("/app/duplicates/delete")
	@ResponseBody
	public DuplicateDeletionProgress delete(@RequestBody DuplicateDeleteRequest request) {
		List<UUID> ids = request == null || request.ids() == null ? List.of() : request.ids();

		if (duplicateDeletionAsyncRunner.start(ids.size())) {
			try {
				duplicateDeletionAsyncRunner.run(ids);
			} catch (TaskRejectedException _) {
				// The @Async task was never submitted (shared executor saturated or shutting
				// down), so run()'s finally never releases the claim - release it here so the
				// screen isn't stuck "in progress" and future deletions can still start.
				duplicateDeletionAsyncRunner.releaseRejectedSubmission();
			}
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
		boolean created = duplicateExclusionService.excludeFile(request == null ? null : request.publicId());

		photoSimilarityService.invalidateCache();
		videoSimilarityWeb.similarityService().invalidateCache();

		return new DuplicateExclusionResponse(created);
	}

	/**
	 * Hides a whole folder (recursively) from duplicate comparison. Same semantics
	 * as {@link #excludeFile(DuplicateExcludeRequest)}: nothing is deleted, both
	 * tabs stop comparing every current and future file at or under the folder.
	 */
	@PostMapping("/app/duplicates/exclude/folder")
	@ResponseBody
	public DuplicateExclusionResponse excludeFolder(@RequestBody DuplicateExcludeRequest request) {
		boolean created = duplicateExclusionService.excludeFolder(request == null ? null : request.folder());

		photoSimilarityService.invalidateCache();
		videoSimilarityWeb.similarityService().invalidateCache();

		return new DuplicateExclusionResponse(created);
	}

	/**
	 * Current snapshot of the background deletion, polled by the screen to drive
	 * the progress bar.
	 */
	@GetMapping("/app/duplicates/delete/progress")
	@ResponseBody
	public DuplicateDeletionProgress deleteProgress() {
		boolean running = duplicateDeletionAsyncRunner.isRunning();

		return new DuplicateDeletionProgress(running, duplicateDeletionAsyncRunner.processed(),
				duplicateDeletionAsyncRunner.total(), duplicateDeletionAsyncRunner.percent(),
				running ? null : duplicateDeletionAsyncRunner.lastResult());
	}

	/**
	 * Renders the Fotos Semelhantes tab without ever blocking on the heavy
	 * grouping: if the result is cached it shows the groups; otherwise it kicks off
	 * the background runner and shows a "Calculando semelhança…" panel that polls
	 * until the compute finishes.
	 */
	private void renderPhotoTab(Model model, int safeMinSimilarity, int page, int pageSize) {
		FingerprintBacklogStatus status = phashBacklogService.status();

		boolean block = status.blocking();

		setBacklogAttributes(model, status, phashBacklogAsyncRunner.etaSeconds(), phashBacklogAsyncRunner.isRunning(),
				PHOTO_ACTION_BASE, block);
		model.addAttribute(ATTR_SIMILARITY_COMPUTING, false);

		if (block) {
			// Do not load partial similarity groups while fingerprints are still computing.
			addPageAttributes(model, Page.empty(), List.of());

			return;
		}

		Optional<Page<SimilarPhotoGroupResponse>> cached = photoSimilarityService.cachedPage(safeMinSimilarity,
				PageRequest.of(page, pageSize));

		if (cached.isPresent()) {
			Page<SimilarPhotoGroupResponse> similarPage = cached.get();

			addPageAttributes(model, similarPage, similarPage.getContent().stream().map(this::toView).toList());

			return;
		}

		if (photoSimilarityAsyncRunner.start(safeMinSimilarity)) {
			photoSimilarityAsyncRunner.run(safeMinSimilarity);
		}

		addPageAttributes(model, Page.empty(), List.of());

		model.addAttribute(ATTR_SIMILARITY_COMPUTING, true);
		model.addAttribute("similarityPercent", photoSimilarityAsyncRunner.percent());
		model.addAttribute("similarityProcessed", photoSimilarityAsyncRunner.processed());
		model.addAttribute("similarityTotal", photoSimilarityAsyncRunner.total());
	}

	private void renderVideoTab(Model model, int safeMinSimilarity, int page, int pageSize) {
		FingerprintBacklogStatus status = videoSimilarityWeb.backlogService().status();

		boolean block = status.blocking();

		setBacklogAttributes(model, status, videoSimilarityWeb.backlogRunner().etaSeconds(),
				videoSimilarityWeb.backlogRunner().isRunning(), VIDEO_ACTION_BASE, block);

		model.addAttribute(ATTR_SIMILARITY_COMPUTING, false);

		if (block) {
			addPageAttributes(model, Page.empty(), List.of());

			return;
		}

		Optional<Page<SimilarVideoGroupResponse>> cached = videoSimilarityWeb.similarityService()
				.cachedPage(safeMinSimilarity, PageRequest.of(page, pageSize));

		if (cached.isPresent()) {
			Page<SimilarVideoGroupResponse> similarPage = cached.get();

			addPageAttributes(model, similarPage, similarPage.getContent().stream().map(this::toView).toList());

			return;
		}

		if (videoSimilarityWeb.similarityRunner().start(safeMinSimilarity)) {
			videoSimilarityWeb.similarityRunner().run(safeMinSimilarity);
		}

		addPageAttributes(model, Page.empty(), List.of());

		model.addAttribute(ATTR_SIMILARITY_COMPUTING, true);
		model.addAttribute("similarityPercent", videoSimilarityWeb.similarityRunner().percent());
		model.addAttribute("similarityProcessed", videoSimilarityWeb.similarityRunner().processed());
		model.addAttribute("similarityTotal", videoSimilarityWeb.similarityRunner().total());
	}

	private void setBacklogAttributes(Model model, FingerprintBacklogStatus status, long etaSeconds, boolean running,
			String actionBase, boolean blocking) {
		model.addAttribute("phashPending", status.pending());
		model.addAttribute("phashDone", status.done());
		model.addAttribute("phashFailed", status.failed());
		model.addAttribute("phashTotal", status.total());
		model.addAttribute("phashPercent", status.percent());
		model.addAttribute("phashEtaSeconds", etaSeconds);
		model.addAttribute("phashBlocking", blocking);
		model.addAttribute("phashRunning", running);
		model.addAttribute("phashRebuildAvailable", !running);
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

	private DuplicateGroupView toView(SimilarPhotoGroupResponse group) {
		String header = group.files() == 1 ? message("backend.duplicates.similarGroup.one", group.files())
				: message("backend.duplicates.similarGroup.many", group.files());

		String badge = message("backend.duplicates.similarBadge", group.similarityPercent(),
				group.wastedSize().formatted());

		return new DuplicateGroupView(group.groupId(), header, badge,
				toFileViews(group.keep(), group.deleteCandidates(), group.reviewCandidates()));
	}

	private DuplicateGroupView toView(SimilarVideoGroupResponse group) {
		String header = group.files() == 1 ? message("backend.duplicates.similarVideoGroup.one", group.files())
				: message("backend.duplicates.similarVideoGroup.many", group.files());

		String badge = message("backend.duplicates.similarVideoBadge", group.similarityPercent(),
				group.wastedSize().formatted());

		return new DuplicateGroupView(group.groupId(), header, badge,
				toFileViews(group.keep(), group.deleteCandidates(), group.reviewCandidates()));
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
				openTitle(pdf, text, audio), dateSourceLabel(file.dateSource()),
				dateSourceBadgeClass(file.dateSource()), DateTimeFormatUtils.human(file.captureDate()),
				DateTimeFormatUtils.human(file.modifiedAt()));
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

	private String dateSourceLabel(DateSource source) {
		if (source == null) {
			return "—";
		}

		return switch (source) {
		case EXIF -> "EXIF";
		case MEDIA_INFO -> message("backend.dateSource.media");
		case FILE_NAME_CONFIRMED -> message("backend.dateSource.nameConfirmed");
		case FILE_NAME -> message("backend.dateSource.name");
		case FOLDER_LAYOUT -> message("backend.dateSource.folder");
		case FILE_MODIFIED_AT -> message("backend.dateSource.file");
		case FILE_CREATED_AT -> message("backend.dateSource.created");
		case UNKNOWN -> message("backend.dateSource.unknown");
		};
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