package br.com.jorgemelo.nimbusfilemanager.organization.infrastructure.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationFallbackMode;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationSubdivision;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.constants.OrganizationConstants;
import br.com.jorgemelo.nimbusfilemanager.organization.application.constants.OrganizationMessages;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.Defaults;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationForm;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.StoredPlanPage;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationConflictType;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.EnumUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.NumberUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SecurityUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SizeFormatter;

@Controller
public class OrganizationWebController extends LocalizedComponent {

	/**
	 * Model attribute holding the sentence the Execute button asks before moving
	 * anything.
	 */
	private static final String EXECUTE_CONFIRMATION = "executeConfirmation";

	private static final String INTERRUPTED = "INTERRUPTED";
	private static final String CANCELLED = "CANCELLED";
	private static final String FINISHED = "FINISHED";
	private static final String FINISHED_WITH_ERRORS = "FINISHED_WITH_ERRORS";
	private static final String ERROR = "ERROR";
	private static final String VIEW = "app/organization";
	private static final String LIMIT = "limit";
	private static final String LOCATION_SUBDIVISION = "locationSubdivision";
	private static final String LOCATION_MIN_CONFIDENCE = "locationMinConfidence";
	private static final String LOCATION_FALLBACK = "locationFallback";
	private static final String SOURCE_PATH = "sourcePath";
	private static final String TARGET_PATH = "targetPath";

	private final OrganizationService organizationService;
	private final UserPagePreferenceService userPagePreferenceService;
	private final ExecutionQueryService executionQueryService;

	@Autowired
	public OrganizationWebController(OrganizationService organizationService,
			UserPagePreferenceService userPagePreferenceService, ExecutionQueryService executionQueryService) {
		this.organizationService = organizationService;
		this.userPagePreferenceService = userPagePreferenceService;
		this.executionQueryService = executionQueryService;
	}

	@GetMapping("/app/organization")
	public String organization(@RequestParam(required = false) String source,
			@RequestParam(required = false) String target, Authentication authentication, Model model) {
		Defaults defaults = loadDefaults(authentication);

		// "Reprocessar" on an interrupted/failed run links here with source/target
		// pre-filled so the
		// user finishes it with their usual settings (layout etc. from defaults) after
		// a quick review.
		String sourcePath = source != null && !source.isBlank() ? source : defaults.sourcePath();
		String targetPath = target != null && !target.isBlank() ? target : defaults.targetPath();

		prepareModel(model,
				new OrganizationForm(sourcePath, targetPath, defaults.recursive(), defaults.layout(), defaults.limit(),
						defaults.allowConflicts(), defaults.overwriteExisting(), 0, defaults.size(),
						defaults.locationSubdivision(), defaults.locationMinConfidence(), defaults.locationFallback()));

		return VIEW;
	}

	public String organization(Authentication authentication, Model model) {
		return organization(null, null, authentication, model);
	}

	public String organization(Model model) {
		return organization(null, null, null, model);
	}

	@PostMapping("/app/organization/preview")
	public String preview(@ModelAttribute OrganizationForm submitted, Authentication authentication, Model model) {
		OrganizationLayout safeLayout = submitted.layout() == null ? OrganizationLayout.DEFAULT : submitted.layout();

		OrganizationForm form = new OrganizationForm(submitted.sourcePath(), submitted.targetPath(),
				submitted.recursive(), safeLayout, submitted.limit(), submitted.allowConflicts(),
				submitted.overwriteExisting(), submitted.page(), submitted.size(), submitted.locationSubdivision(),
				submitted.locationMinConfidence(), submitted.locationFallback());

		saveDefaults(authentication, form);

		prepareModel(model, form);

		String validationError = validateOrganizationPaths(form.sourcePath(), form.targetPath());

		if (validationError != null) {
			model.addAttribute(SharedConstants.ATTR_ERROR, validationError);

			return VIEW;
		}

		// Preview is a dry-run of execute: build the very same request the execute POST
		// would (including allowConflicts/overwrite the form already collects) with
		// dryRun=true, so the preview simulates the real move per file.
		var request = new OrganizationExecuteRequest(form.sourcePath(), form.targetPath(), form.recursive(), safeLayout,
				form.limit(), false, null, true, null, null, null, null, form.allowConflicts(),
				form.overwriteExisting(), form.locationSubdivision(), form.locationMinConfidence(),
				form.locationFallback(), true);

		try {
			var started = organizationService.previewAsync(request);

			return "redirect:/app/progress/" + started.executionId() + "?kind=organization-preview";
		} catch (IllegalArgumentException exception) {
			model.addAttribute(SharedConstants.ATTR_ERROR, exception.getMessage());

			return VIEW;
		}
	}

	public String preview(OrganizationForm form, Model model) {
		return preview(form, null, model);
	}

	@GetMapping("/app/organization/preview/{executionId}")
	public String previewResult(@PathVariable UUID executionId, @RequestParam(defaultValue = "0") Integer page,
			@RequestParam(defaultValue = "50") Integer size,
			@RequestParam(defaultValue = "false") boolean onlyConflicts, Authentication authentication, Model model) {
		Defaults defaults = loadDefaults(authentication);

		Optional<StoredPlanPage> plan = organizationService.planPagePublic(executionId, safePage(page), safeSize(size),
				onlyConflicts);

		if (plan.isEmpty()) {
			restoreForm(model, defaults, defaults.sourcePath(), defaults.targetPath(), defaults.layout(), 0,
					defaults.size());

			model.addAttribute(SharedConstants.ATTR_ERROR, previewMissingMessage(executionId));
			model.addAttribute("errorProgressId", executionId);

			return VIEW;
		}

		restoreForm(model, defaults, plan.get().sourcePath(), plan.get().targetPath(), plan.get().layout(), page, size);

		model.addAttribute("executionId", executionId);

		addPlan(model, plan.get(), onlyConflicts);

		return VIEW;
	}

	private String previewMissingMessage(UUID executionId) {
		String status = executionStatus(executionId);

		if (ERROR.equals(status) || FINISHED_WITH_ERRORS.equals(status)) {
			return message("backend.organization.previewError", executionId);
		}

		if (FINISHED.equals(status) || CANCELLED.equals(status) || INTERRUPTED.equals(status)) {
			return message("backend.organization.previewExpired", executionId);
		}

		if (status != null) {
			return message("backend.organization.previewProcessing", executionId);
		}

		return message("backend.organization.previewNotFound", executionId);
	}

	private String executionStatus(UUID executionId) {
		try {
			return executionQueryService.get(executionId).status();
		} catch (RuntimeException _) {
			return null;
		}
	}
	/**
	 * The package-private overload the tests drive, by internal id. It reads the
	 * same published rows the public one does - there is no second path into a
	 * plan.
	 */
	String previewResult(Long executionId, Integer page, Integer size, Model model) {
		Defaults defaults = loadDefaults(null);

		Optional<StoredPlanPage> plan = organizationService.planPage(executionId, safePage(page), safeSize(size),
				false);

		if (plan.isEmpty()) {
			restoreForm(model, defaults, defaults.sourcePath(), defaults.targetPath(), defaults.layout(), 0,
					defaults.size());

			model.addAttribute(SharedConstants.ATTR_ERROR,
					message("backend.organization.previewNotFound", executionId));

			return VIEW;
		}

		restoreForm(model, defaults, plan.get().sourcePath(), plan.get().targetPath(), plan.get().layout(), page, size);

		model.addAttribute("executionId", executionId);

		addPlan(model, plan.get(), false);

		return VIEW;
	}

	@PostMapping("/app/organization/execute")
	public String execute(@ModelAttribute OrganizationForm submitted, Authentication authentication, Model model) {
		OrganizationLayout safeLayout = submitted.layout() == null ? OrganizationLayout.DEFAULT : submitted.layout();

		// Execute never persists a page size (null size) and always renders the first
		// page at the default size, so safeSize(null) resolves back to 50.
		OrganizationForm form = new OrganizationForm(submitted.sourcePath(), submitted.targetPath(),
				submitted.recursive(), safeLayout, submitted.limit(), submitted.allowConflicts(),
				submitted.overwriteExisting(), 0, null, submitted.locationSubdivision(),
				submitted.locationMinConfidence(), submitted.locationFallback());

		saveDefaults(authentication, form);

		prepareModel(model, form);

		String validationError = validateOrganizationPaths(form.sourcePath(), form.targetPath());

		if (validationError != null) {
			model.addAttribute(SharedConstants.ATTR_ERROR, validationError);

			return VIEW;
		}

		var request = new OrganizationExecuteRequest(form.sourcePath(), form.targetPath(), form.recursive(), safeLayout,
				form.limit(), false, null, true, null, null, null, null, form.allowConflicts(),
				form.overwriteExisting(), form.locationSubdivision(), form.locationMinConfidence(),
				form.locationFallback());

		try {
			var started = organizationService.executeAsync(request);

			return "redirect:/app/progress/" + started.executionId() + "?kind=organization-execute";
		} catch (IllegalArgumentException exception) {
			model.addAttribute(SharedConstants.ATTR_ERROR, exception.getMessage());

			return VIEW;
		}
	}

	public String execute(OrganizationForm form, Model model) {
		return execute(form, null, model);
	}

	/**
	 * Repopulates the whole form for the post-preview reload: source/target/layout
	 * come from the plan (authoritative for what was previewed), everything else
	 * from the user's saved preferences (persisted on the preview submit), so no
	 * field silently reverts to a default.
	 */
	private void restoreForm(Model model, Defaults defaults, String sourcePath, String targetPath,
			OrganizationLayout layout, Integer page, Integer size) {
		prepareModel(model,
				new OrganizationForm(sourcePath, targetPath, defaults.recursive(), layout, defaults.limit(),
						defaults.allowConflicts(), defaults.overwriteExisting(), page, size,
						defaults.locationSubdivision(), defaults.locationMinConfidence(), defaults.locationFallback()));
	}

	private void prepareModel(Model model, OrganizationForm form) {
		model.addAttribute("layouts", Arrays.stream(OrganizationLayout.values())
				.filter(value -> value != OrganizationLayout.DEFAULT).toList());
		model.addAttribute(SOURCE_PATH, form.sourcePath());
		model.addAttribute(TARGET_PATH, form.targetPath());
		model.addAttribute(OrganizationConstants.RECURSIVE, form.recursive());
		model.addAttribute("layoutValue", form.layout() == null ? OrganizationLayout.DEFAULT : form.layout());
		model.addAttribute(LIMIT, Objects.requireNonNullElse(form.limit(), 1000));
		model.addAttribute(OrganizationConstants.ALLOW_CONFLICTS, form.allowConflicts());
		model.addAttribute(OrganizationConstants.OVERWRITE_EXISTING, form.overwriteExisting());
		model.addAttribute("page", safePage(form.page()));
		model.addAttribute("size", safeSize(form.size()));
		model.addAttribute("sizes", List.of(20, 50, 100));

		// The confirmation before a preview cannot say how much will move, because
		// nothing has been counted yet. addPlan replaces it with the sentence that
		// can. Which of the two applies is decided here rather than in the template:
		// the screen displays a sentence, it does not choose one.
		model.addAttribute(EXECUTE_CONFIRMATION, message(OrganizationMessages.EXECUTE_CONFIRM));

		prepareLocationModel(model, form.locationSubdivision(), form.locationMinConfidence(), form.locationFallback());
	}

	/**
	 * Options and selected values for the geographic subdivision. Defaults: NONE /
	 * Qualquer / Ignorar when nothing was chosen/saved yet.
	 */
	private void prepareLocationModel(Model model, LocationSubdivision subdivision, LocationConfidence minConfidence,
			LocationFallbackMode fallback) {
		model.addAttribute("locationSubdivisions", LocationSubdivision.values());
		model.addAttribute("locationConfidences", LocationConfidence.values());
		model.addAttribute("locationFallbacks", LocationFallbackMode.values());
		model.addAttribute("locationSubdivisionValue", subdivision == null ? LocationSubdivision.NONE : subdivision);
		model.addAttribute("locationMinConfidenceValue", minConfidence);
		model.addAttribute("locationFallbackValue", fallback == null ? LocationFallbackMode.IGNORE : fallback);
	}

	/**
	 * Renders one page of a published plan.
	 *
	 * <p>
	 * The paging is done by the read, not here: the page arrives already narrowed
	 * to fifty rows out of what may be a hundred thousand, and the summary cards
	 * come from the plan's own counts. "Only conflicts" is likewise a different
	 * query rather than a filter over rows this process would otherwise have to
	 * hold.
	 */
	private void addPlan(Model model, StoredPlanPage plan, boolean onlyConflicts) {
		int totalPages = plan.totalItems() == 0 ? 1 : (int) Math.ceil((double) plan.totalItems() / plan.size());

		model.addAttribute("plan", plan);

		// Now there is a count and a weight, so the question stops being generic:
		// "move 9,600 files, 412 GB?" is a different decision from "organize now?".
		model.addAttribute(EXECUTE_CONFIRMATION, message(OrganizationMessages.EXECUTE_CONFIRM_PLANNED,
				plan.summary().plannedMoves(), SizeFormatter.format(plan.summary().totalSizeBytes())));

		model.addAttribute("previewItems", plan.items());
		model.addAttribute("conflictTypeLabels", conflictTypeLabels());
		model.addAttribute("page", plan.page());
		model.addAttribute("size", plan.size());
		model.addAttribute("totalItems", plan.totalItems());
		model.addAttribute("totalPages", totalPages);
		model.addAttribute("hasPrevious", plan.page() > 0);
		model.addAttribute("hasNext", plan.page() + 1 < totalPages);
		model.addAttribute("onlyConflicts", onlyConflicts);

		// The run recalculates rather than replaying this plan, so the library having
		// moved since is normal - and saying so is what keeps it from being a surprise
		// afterwards.
		model.addAttribute("catalogChanged", plan.catalogChanged());
	}

	/**
	 * Localized conflict-type labels by enum name, so the preview table renders the
	 * raw {@link OrganizationConflictType} name each item carries as text the user
	 * reads without the front translating anything.
	 */
	private Map<String, String> conflictTypeLabels() {
		Map<String, String> labels = new LinkedHashMap<>();

		for (OrganizationConflictType type : OrganizationConflictType.values()) {
			labels.put(type.name(), conflictTypeLabel(type));
		}

		return labels;
	}

	private String conflictTypeLabel(OrganizationConflictType type) {
		return switch (type) {
		case TARGET_EXISTS -> message("enum.organizationConflictType.TARGET_EXISTS");
		case DUPLICATE_TARGET -> message("enum.organizationConflictType.DUPLICATE_TARGET");
		case TARGET_EXISTS_AND_DUPLICATE -> message("enum.organizationConflictType.TARGET_EXISTS_AND_DUPLICATE");
		};
	}

	private String validateOrganizationPaths(String sourcePath, String targetPath) {
		if (sourcePath == null || sourcePath.isBlank()) {
			return message("backend.organization.sourceRequired");
		}

		if (targetPath == null || targetPath.isBlank()) {
			return message("backend.organization.targetRequired");
		}

		Path source = Path.of(sourcePath).toAbsolutePath().normalize();

		if (!Files.isDirectory(source)) {
			return message("backend.organization.sourceInvalid", source);
		}

		Path target = Path.of(targetPath).toAbsolutePath().normalize();

		if (source.equals(target)) {
			return message("backend.organization.pathsMustDiffer");
		}

		return null;
	}

	private int safePage(Integer page) {
		return page == null || page < 0 ? 0 : page;
	}

	private int safeSize(Integer size) {
		if (size == null) {
			return 50;
		}

		return switch (size) {
		case 20, 50, 100 -> size;
		default -> 50;
		};
	}

	/**
	 * Every field the form should start with, backed by UserPagePreferenceService
	 * (page key "organization") - the same store Arquivos already uses - so both a
	 * preview/execute submit here and an edit on the Preferencias tab of
	 * Configuracoes update the same values. sourcePath/targetPath are saved the
	 * same way but, like Arquivos' own path preference, aren't surfaced on that
	 * Preferencias tab - only re-read here so the form doesn't come back empty
	 * after leaving and reopening the app.
	 */

	private Defaults loadDefaults(Authentication authentication) {
		Map<String, String> preferences = userPagePreferenceService.find(username(authentication),
				OrganizationConstants.PAGE_KEY);

		if (preferences == null) {
			preferences = Map.of();
		}

		boolean recursive = !preferences.containsKey(OrganizationConstants.RECURSIVE)
				|| Boolean.parseBoolean(preferences.get(OrganizationConstants.RECURSIVE));
		boolean allowConflicts = Boolean.parseBoolean(preferences.get(OrganizationConstants.ALLOW_CONFLICTS));
		boolean overwriteExisting = Boolean.parseBoolean(preferences.get(OrganizationConstants.OVERWRITE_EXISTING));

		OrganizationLayout layout = parseLayout(preferences.get(OrganizationConstants.LAYOUT));

		Integer size = parseSize(preferences.get(OrganizationConstants.SIZE));
		Integer limit = parseLimit(preferences.get(LIMIT));

		LocationSubdivision subdivision = parseSubdivision(preferences.get(LOCATION_SUBDIVISION));
		LocationConfidence minConfidence = parseConfidence(preferences.get(LOCATION_MIN_CONFIDENCE));
		LocationFallbackMode fallback = parseFallback(preferences.get(LOCATION_FALLBACK));

		return new Defaults(recursive, layout, allowConflicts, overwriteExisting, Objects.requireNonNullElse(size, 50),
				Objects.requireNonNullElse(limit, 1000), preferences.get(SOURCE_PATH), preferences.get(TARGET_PATH),
				subdivision, minConfidence, fallback);
	}

	private void saveDefaults(Authentication authentication, OrganizationForm form) {
		String username = username(authentication);

		userPagePreferenceService.save(username, OrganizationConstants.PAGE_KEY, OrganizationConstants.RECURSIVE,
				Boolean.toString(form.recursive()));
		userPagePreferenceService.save(username, OrganizationConstants.PAGE_KEY, OrganizationConstants.LAYOUT,
				form.layout().name());
		userPagePreferenceService.save(username, OrganizationConstants.PAGE_KEY, OrganizationConstants.ALLOW_CONFLICTS,
				Boolean.toString(form.allowConflicts()));
		userPagePreferenceService.save(username, OrganizationConstants.PAGE_KEY,
				OrganizationConstants.OVERWRITE_EXISTING, Boolean.toString(form.overwriteExisting()));

		if (form.size() != null) {
			userPagePreferenceService.save(username, OrganizationConstants.PAGE_KEY, OrganizationConstants.SIZE,
					form.size().toString());
		}

		if (form.limit() != null) {
			userPagePreferenceService.save(username, OrganizationConstants.PAGE_KEY, LIMIT, form.limit().toString());
		}

		if (form.locationSubdivision() != null) {
			userPagePreferenceService.save(username, OrganizationConstants.PAGE_KEY, LOCATION_SUBDIVISION,
					form.locationSubdivision().name());
		}

		// "Qualquer" (null) is a real choice, so persist it as an empty value to
		// overwrite any previously saved confidence instead of leaving the old one.
		userPagePreferenceService.save(username, OrganizationConstants.PAGE_KEY, LOCATION_MIN_CONFIDENCE,
				form.locationMinConfidence() == null ? "" : form.locationMinConfidence().name());

		if (form.locationFallback() != null) {
			userPagePreferenceService.save(username, OrganizationConstants.PAGE_KEY, LOCATION_FALLBACK,
					form.locationFallback().name());
		}

		if (form.sourcePath() != null && !form.sourcePath().isBlank()) {
			userPagePreferenceService.save(username, OrganizationConstants.PAGE_KEY, SOURCE_PATH, form.sourcePath());
		}

		if (form.targetPath() != null && !form.targetPath().isBlank()) {
			userPagePreferenceService.save(username, OrganizationConstants.PAGE_KEY, TARGET_PATH, form.targetPath());
		}
	}

	private OrganizationLayout parseLayout(String value) {
		return EnumUtils.valueOfOrDefault(OrganizationLayout.class, value, OrganizationLayout.DEFAULT);
	}

	private Integer parseSize(String value) {
		return NumberUtils.parseIntOrNull(value);
	}

	private Integer parseLimit(String value) {
		return parseSize(value);
	}

	private LocationSubdivision parseSubdivision(String value) {
		return EnumUtils.valueOfOrNull(LocationSubdivision.class, value);
	}

	private LocationConfidence parseConfidence(String value) {
		return EnumUtils.valueOfOrNull(LocationConfidence.class, value);
	}

	private LocationFallbackMode parseFallback(String value) {
		return EnumUtils.valueOfOrNull(LocationFallbackMode.class, value);
	}

	private String username(Authentication authentication) {
		return SecurityUtils.usernameOr(authentication, null);
	}
}