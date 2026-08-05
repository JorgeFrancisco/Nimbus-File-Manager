package br.com.jorgemelo.nimbusfilemanager.quarantine.infrastructure.web;

import static br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants.PAGE_SIZE_KEY;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineCleanupLauncher;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineLauncherService;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineListing;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineProgressService;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineRestoreLauncher;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineCleanupResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineItemResponse;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineProgress;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreOptions;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreRequest;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreSelectedRequest;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.ConflictResolution;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PageUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SecurityUtils;

/**
 * Renders the "Quarentena" screen and takes what is asked of it. The listing is
 * a flat, newest-first set of still-quarantined files (driven by the quarantine
 * {@code Movement} rows - see {@link QuarantineListing}) rendered with the
 * shared media-card fragment, so it gets the same thumbnails, view modes and
 * open-in-lightbox behaviour as the Arquivos explorer. Thumbnails and content
 * come from {@code /api/media/...} by public id (which now serves soft-deleted
 * files too, to logged-in users). Restores post JSON back so the page can
 * report per-file outcomes and refresh without a full reload.
 *
 * <p>
 * Nothing here moves or deletes a file. What each endpoint holds is either a
 * reading or a launcher - the work itself is claimed from the queue by a
 * worker, which is why a screen can no longer reach the capability to change
 * somebody's files while a request is open.
 */
@Controller
public class QuarantineWebController extends LocalizedComponent {

	private static final List<Integer> PAGE_SIZES = List.of(50, 100, 200);
	private static final int DEFAULT_PAGE_SIZE = 50;
	private static final String VIEW = "view";
	private static final Set<String> VIEW_MODES = Set.of("details", "small", "large", "xlarge");

	private final QuarantineListing quarantineListing;
	private final QuarantineRestoreLauncher quarantineRestoreLauncher;
	private final QuarantineCleanupLauncher quarantineCleanupLauncher;
	private final QuarantineLauncherService quarantineLauncherService;
	private final QuarantineProgressService quarantineProgressService;
	private final UserPagePreferenceService userPagePreferenceService;

	public QuarantineWebController(QuarantineListing quarantineListing,
			QuarantineRestoreLauncher quarantineRestoreLauncher, QuarantineCleanupLauncher quarantineCleanupLauncher,
			QuarantineLauncherService quarantineLauncherService,
			QuarantineProgressService quarantineProgressService,
			UserPagePreferenceService userPagePreferenceService) {
		this.quarantineListing = quarantineListing;
		this.quarantineRestoreLauncher = quarantineRestoreLauncher;
		this.quarantineCleanupLauncher = quarantineCleanupLauncher;
		this.quarantineLauncherService = quarantineLauncherService;
		this.quarantineProgressService = quarantineProgressService;
		this.userPagePreferenceService = userPagePreferenceService;
	}

	@GetMapping("/app/quarantine")
	public String quarantine(@RequestParam(defaultValue = "0") int page, @RequestParam(required = false) String view,
			@RequestParam(required = false) Integer size, Authentication authentication, Model model) {
		String username = SecurityUtils.usernameOr(authentication, null);

		Map<String, String> preferences = userPagePreferenceService.find(username, QuarantineConstants.PAGE_KEY);

		if (preferences == null) {
			preferences = Map.of();
		}

		String requestedView = view != null ? view : preferences.get(VIEW);

		String viewMode = requestedView != null && VIEW_MODES.contains(requestedView) ? requestedView : "details";

		int pageSize = resolvePageSize(size, username, preferences);

		if (view != null && VIEW_MODES.contains(view)) {
			userPagePreferenceService.save(username, QuarantineConstants.PAGE_KEY, VIEW, view);
		}

		Page<QuarantineItemResponse> items = quarantineListing.list(PageRequest.of(Math.max(page, 0), pageSize));

		model.addAttribute("items", items.getContent());
		model.addAttribute("viewMode", viewMode);
		model.addAttribute("hasAbsent", items.getContent().stream().anyMatch(item -> !item.presentInQuarantine()));
		model.addAttribute("pageNumber", items.getNumber());
		model.addAttribute("hasPrevious", items.getNumber() > 0);
		model.addAttribute("hasNext", items.hasNext());
		model.addAttribute("totalElements", items.getTotalElements());
		model.addAttribute("totalPages", items.getTotalPages());
		model.addAttribute(PAGE_SIZE_KEY, pageSize);
		model.addAttribute("pageSizes", PAGE_SIZES);

		return "app/quarantine";
	}

	private int resolvePageSize(Integer requested, String username, Map<String, String> preferences) {
		if (requested != null && PAGE_SIZES.contains(requested)) {
			userPagePreferenceService.save(username, QuarantineConstants.PAGE_KEY, PAGE_SIZE_KEY, requested.toString());

			return requested;
		}

		return PageUtils.validSizeOrDefault(preferences.get(PAGE_SIZE_KEY), PAGE_SIZES, DEFAULT_PAGE_SIZE);
	}

	/**
	 * Restores one quarantined file with the chosen conflict handling and optional
	 * alternate folder. The answer is either the question to put back to the
	 * person, or what the restore did - it is queued, and only waited on long
	 * enough for the ordinary case to still feel like a reply.
	 */
	@PostMapping("/app/quarantine/restore")
	@ResponseBody
	public QuarantineRestoreResult restore(@RequestBody QuarantineRestoreRequest request) {
		if (request == null || request.movementId() == null) {
			return new QuarantineRestoreResult(false, "ERROR", message("backend.quarantine.invalidRequest"), null,
					null);
		}

		Path destinationFolder = request.destinationFolder() == null || request.destinationFolder().isBlank() ? null
				: PathUtils.normalizePath(request.destinationFolder());

		return quarantineRestoreLauncher.restore(request.movementId(),
				new QuarantineRestoreOptions(destinationFolder, conflictResolution(request.conflict())));
	}

	/**
	 * Restores every selected file at once, using safe defaults; items needing a
	 * decision come back untouched.
	 */
	@PostMapping("/app/quarantine/restore-selected")
	@ResponseBody
	public QuarantineProgress restoreSelected(@RequestBody QuarantineRestoreSelectedRequest request) {
		if (request == null || request.ids() == null || request.ids().isEmpty()) {
			return quarantineProgressService.snapshot();
		}

		return launching(() -> quarantineLauncherService.launchRestore(request.ids()));
	}

	/**
	 * Permanently deletes the selected quarantined files now (physical file +
	 * records). Irreversible.
	 */
	@PostMapping("/app/quarantine/delete-selected")
	@ResponseBody
	public QuarantineProgress deleteSelected(@RequestBody QuarantineRestoreSelectedRequest request) {
		if (request == null || request.ids() == null || request.ids().isEmpty()) {
			return quarantineProgressService.snapshot();
		}

		return launching(() -> quarantineLauncherService.launchPurge(request.ids()));
	}

	/**
	 * What the last batch is doing, or said. The screen polls it because the work
	 * happens in the worker now and there is no response to wait on.
	 */
	@GetMapping("/app/quarantine/progress")
	@ResponseBody
	public QuarantineProgress progress() {
		return quarantineProgressService.snapshot();
	}

	/**
	 * Removes the records of items whose file is no longer in quarantine
	 * ("Ausente"). What is absent is read here and re-checked by the worker under
	 * each item's lock, so a transient drive outage - which makes every item look
	 * absent at once - never wipes real entries.
	 */
	@PostMapping("/app/quarantine/cleanup-absent")
	@ResponseBody
	public QuarantineCleanupResult cleanupAbsent() {
		return quarantineCleanupLauncher.clearAbsent();
	}

	/**
	 * Queues a batch and answers with the snapshot the screen polls - or, when the
	 * request could not be made at all, with the reason in that same snapshot.
	 *
	 * <p>
	 * The refusal has to arrive as data because this controller serves the screen
	 * rather than the API, and the advice that turns a refusal into a message
	 * covers the REST packages. Left to escape, what the person would get for a
	 * quarantine folder they never configured is a bare 500.
	 */
	private QuarantineProgress launching(Runnable batch) {
		try {
			batch.run();

			return quarantineProgressService.snapshot();
		} catch (IllegalArgumentException refusal) {
			return quarantineProgressService.refused(refusal.getMessage());
		}
	}

	private ConflictResolution conflictResolution(String raw) {
		if (raw == null || raw.isBlank()) {
			return ConflictResolution.BLOCK;
		}

		try {
			return ConflictResolution.valueOf(raw.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException _) {
			return ConflictResolution.BLOCK;
		}
	}
}