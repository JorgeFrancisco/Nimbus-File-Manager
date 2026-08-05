package br.com.jorgemelo.nimbusfilemanager.settings.infrastructure.web;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.InventoryWatchService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.LibrarySwitchLauncher;
import br.com.jorgemelo.nimbusfilemanager.settings.application.QuarantineFolderPolicy;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SecurityUtils;

/**
 * Writes of the Sistema tab parameters, kept apart from
 * {@link SettingsWebController} - which renders the page and owns the personal
 * preferences - the same way the geo, duplicate-exclusion and retention actions
 * live in their own siblings. Splitting it also keeps each constructor within
 * the injection budget: this handler needs the inventory and library services
 * the page render has no use for.
 */
@Controller
@Profile(NimbusProfiles.APP)
public class SettingsParameterWebController extends LocalizedComponent {

	private final AppSettingService appSettingService;
	private final InventoryWatchService inventoryWatchService;
	private final LibrarySwitchLauncher librarySwitchLauncher;
	private final InventoryRunningState inventoryRunningState;
	private final QuarantineFolderPolicy quarantineFolderPolicy;

	public SettingsParameterWebController(AppSettingService appSettingService,
			InventoryWatchService inventoryWatchService, LibrarySwitchLauncher librarySwitchLauncher,
			InventoryRunningState inventoryRunningState, QuarantineFolderPolicy quarantineFolderPolicy) {
		this.appSettingService = appSettingService;
		this.inventoryWatchService = inventoryWatchService;
		this.librarySwitchLauncher = librarySwitchLauncher;
		this.inventoryRunningState = inventoryRunningState;
		this.quarantineFolderPolicy = quarantineFolderPolicy;
	}

	@PostMapping("/app/settings")
	public String update(@RequestParam String key, @RequestParam String value,
			@RequestParam(defaultValue = "false") boolean confirmLibraryChange, Authentication authentication,
			RedirectAttributes redirectAttributes) {
		if (inventoryRunningState.isRunning()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR,
					message("backend.settings.inventoryBlocked"));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		try {
			if (SettingsConstants.TRASH_FOLDER.equals(key)) {
				quarantineFolderPolicy.validateQuarantineFolder(value);
			}

			if (SettingsConstants.WATCH_FOLDER.equals(key)) {
				String oldFolder = appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "");
				if (!oldFolder.equalsIgnoreCase(value.trim())) {
					if (!confirmLibraryChange) {
						throw new IllegalArgumentException(message("backend.settings.confirmLibrarySwitch"));
					}

					librarySwitchLauncher.validateNewFolder(value);

					quarantineFolderPolicy.validateLibraryFolder(value);

					librarySwitchLauncher.launch(oldFolder, value.trim(), username(authentication));

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

	private String username(Authentication authentication) {
		return SecurityUtils.usernameOr(authentication, "system");
	}
}