package br.com.jorgemelo.nimbusfilemanager.settings.infrastructure.web;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateExclusionService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.geolocation.infrastructure.web.GeoDatasetSettingsModel;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.InventoryWatchService;
import br.com.jorgemelo.nimbusfilemanager.media.application.explorer.FileExplorerService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.constants.OrganizationConstants;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppTimeZones;
import br.com.jorgemelo.nimbusfilemanager.settings.application.LibrarySwitchService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.UpdatePreferencesForm;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.EnumUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SecurityUtils;

/**
 * "Configuracoes" has two tabs sharing one template (app/settings.html):
 * "Sistema" - the AppSettingService-backed parameters below, admin-only both in
 * the sidebar and at /app/settings itself (SecurityConfig) - and "Preferencias"
 * - personal defaults for Arquivos/Organizacao, open to every authenticated
 * user at /app/settings/preferences. The two routes render the same page with a
 * different activeTab so the tab strip and layout never drift apart between the
 * admin and non-admin views.
 *
 * <p>
 * The section-specific write actions of the Sistema tab live in sibling
 * controllers ({@link SettingsGeodataWebController},
 * {@link SettingsDuplicateExclusionWebController},
 * {@link SettingsExecutionRetentionWebController}) and the geo read model in
 * {@link GeoDatasetSettingsModel}, keeping this controller focused on the page
 * render, the system-parameter update and the personal preferences.
 */
@Controller
public class SettingsWebController extends LocalizedComponent {

	private static final List<Integer> PAGE_SIZES = List.of(20, 50, 100);

	private final AppSettingService appSettingService;
	private final InventoryWatchService inventoryWatchService;
	private final LibrarySwitchService librarySwitchService;
	private final InventoryRunningState inventoryRunningState;
	private final DuplicateExclusionService duplicateExclusionService;
	private final UserPagePreferenceService userPagePreferenceService;
	private final GeoDatasetSettingsModel geoDatasetSettingsModel;

	@Autowired
	public SettingsWebController(AppSettingService appSettingService, InventoryWatchService inventoryWatchService,
			LibrarySwitchService librarySwitchService, InventoryRunningState inventoryRunningState,
			DuplicateExclusionService duplicateExclusionService, UserPagePreferenceService userPagePreferenceService,
			GeoDatasetSettingsModel geoDatasetSettingsModel) {
		this.appSettingService = appSettingService;
		this.inventoryWatchService = inventoryWatchService;
		this.librarySwitchService = librarySwitchService;
		this.inventoryRunningState = inventoryRunningState;
		this.duplicateExclusionService = duplicateExclusionService;
		this.userPagePreferenceService = userPagePreferenceService;
		this.geoDatasetSettingsModel = geoDatasetSettingsModel;
	}

	@GetMapping("/app/settings")
	public String settings(Authentication authentication, Model model) {
		model.addAttribute("settings", appSettingService.list());
		model.addAttribute("timezones", AppTimeZones.OPTIONS);
		model.addAttribute("activeTab", "system");
		model.addAttribute("duplicateFileExclusions", duplicateExclusionService.fileExclusions());
		model.addAttribute("duplicateFolderExclusions", duplicateExclusionService.folderExclusions());

		geoDatasetSettingsModel.addTo(model, authentication);
		addPreferencesModel(model, authentication);

		return "app/settings";
	}

	public String settings(Model model) {
		return settings(null, model);
	}

	@PostMapping("/app/settings")
	public String update(@RequestParam String key, @RequestParam String value,
			@RequestParam(defaultValue = "false") boolean confirmLibraryChange, Authentication authentication,
			RedirectAttributes redirectAttributes) {
		if (inventoryRunningState.isRunning()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR, message("backend.settings.inventoryBlocked"));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		try {
			if (SettingsConstants.WATCH_FOLDER.equals(key)) {
				String oldFolder = appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "");
				if (!oldFolder.equalsIgnoreCase(value.trim())) {
					if (!confirmLibraryChange) {
						throw new IllegalArgumentException(message("backend.settings.confirmLibrarySwitch"));
					}

					librarySwitchService.validateNewFolder(value);

					librarySwitchService.switchLibrary(oldFolder, value.trim(), username(authentication));

					redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS,
							message("backend.settings.librarySwitchStarted"));

					return SharedConstants.REDIRECT_SETTINGS;
				}
			}

			appSettingService.update(key, value, username(authentication));

			if (key.startsWith("nimbus-file-manager.inventory.watch-")) {
				inventoryWatchService.reconfigureAndInventory();
			}

			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS, message("backend.settings.updated"));
		} catch (IllegalArgumentException e) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR, e.getMessage());
		}

		return SharedConstants.REDIRECT_SETTINGS;
	}

	@GetMapping("/app/settings/preferences")
	public String preferences(Authentication authentication, Model model) {
		model.addAttribute("activeTab", "preferences");

		addPreferencesModel(model, authentication);

		return "app/settings";
	}

	@PostMapping("/app/settings/preferences")
	public String updatePreferences(@ModelAttribute UpdatePreferencesForm form, Authentication authentication,
			RedirectAttributes redirectAttributes) {
		String username = username(authentication);

		if (form.filesView() != null) {
			userPagePreferenceService.save(username, SharedConstants.FILES_PAGE_KEY, "view", form.filesView());
		}

		if (form.filesSize() != null) {
			userPagePreferenceService.save(username, SharedConstants.FILES_PAGE_KEY, "size",
					form.filesSize().toString());
		}

		userPagePreferenceService.save(username, SharedConstants.LAYOUT_PAGE_KEY, SharedConstants.THEME_PREFERENCE_KEY,
				SharedConstants.THEME_DARK.equals(form.theme()) ? SharedConstants.THEME_DARK
						: SharedConstants.THEME_LIGHT);

		userPagePreferenceService.save(username, OrganizationConstants.PAGE_KEY,
				OrganizationConstants.RECURSIVE, Boolean.toString(Boolean.TRUE.equals(form.organizationRecursive())));

		userPagePreferenceService.save(username, OrganizationConstants.PAGE_KEY,
				OrganizationConstants.ALLOW_CONFLICTS,
				Boolean.toString(Boolean.TRUE.equals(form.organizationAllowConflicts())));

		userPagePreferenceService.save(username, OrganizationConstants.PAGE_KEY,
				OrganizationConstants.OVERWRITE_EXISTING,
				Boolean.toString(Boolean.TRUE.equals(form.organizationOverwriteExisting())));

		if (form.organizationLayout() != null) {
			userPagePreferenceService.save(username, OrganizationConstants.PAGE_KEY,
					OrganizationConstants.LAYOUT, form.organizationLayout().name());
		}

		if (form.organizationSize() != null) {
			userPagePreferenceService.save(username, OrganizationConstants.PAGE_KEY, OrganizationConstants.SIZE,
					form.organizationSize().toString());
		}

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS, message("backend.settings.preferencesUpdated"));

		return "redirect:/app/settings/preferences";
	}

	private void addPreferencesModel(Model model, Authentication authentication) {
		model.addAttribute("fileViewSizes", PAGE_SIZES);
		model.addAttribute("organizationLayouts", Arrays.stream(OrganizationLayout.values())
				.filter(value -> value != OrganizationLayout.DEFAULT).toList());

		String username = username(authentication);

		Map<String, String> filesPreferences = userPagePreferenceService.find(username, SharedConstants.FILES_PAGE_KEY);
		Map<String, String> organizationPreferences = userPagePreferenceService.find(username,
				OrganizationConstants.PAGE_KEY);
		Map<String, String> layoutPreferences = userPagePreferenceService.find(username,
				SharedConstants.LAYOUT_PAGE_KEY);

		model.addAttribute("themeValue",
				SharedConstants.THEME_DARK.equals(layoutPreferences.get(SharedConstants.THEME_PREFERENCE_KEY))
						? SharedConstants.THEME_DARK
						: SharedConstants.THEME_LIGHT);
		model.addAttribute("filesView", filesPreferences.getOrDefault("view", "details"));
		model.addAttribute("filesSize",
				intOrDefault(filesPreferences.get("size"), FileExplorerService.PAGE_SIZES.get(0)));
		model.addAttribute("organizationRecursive",
				!organizationPreferences.containsKey(OrganizationConstants.RECURSIVE)
						|| Boolean.parseBoolean(organizationPreferences.get(OrganizationConstants.RECURSIVE)));
		model.addAttribute("organizationAllowConflicts",
				Boolean.parseBoolean(organizationPreferences.get(OrganizationConstants.ALLOW_CONFLICTS)));
		model.addAttribute("organizationOverwriteExisting",
				Boolean.parseBoolean(organizationPreferences.get(OrganizationConstants.OVERWRITE_EXISTING)));
		model.addAttribute("organizationLayoutValue",
				parseLayout(organizationPreferences.get(OrganizationConstants.LAYOUT)));
		model.addAttribute("organizationSize",
				intOrDefault(organizationPreferences.get(OrganizationConstants.SIZE), 50));
	}

	private OrganizationLayout parseLayout(String value) {
		return EnumUtils.valueOfOrDefault(OrganizationLayout.class, value, OrganizationLayout.DEFAULT);
	}

	private int intOrDefault(String value, int defaultValue) {
		if (value == null || value.isBlank()) {
			return defaultValue;
		}

		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException _) {
			return defaultValue;
		}
	}

	private String username(Authentication authentication) {
		return SecurityUtils.usernameOr(authentication, "system");
	}
}