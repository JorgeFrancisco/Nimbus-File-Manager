package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.BackgroundJobActivity;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintActivityService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryWatchStatus;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.InventoryWatchService;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.security.domain.enums.Role;
import br.com.jorgemelo.nimbusfilemanager.security.domain.model.AppUser;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateCheckService;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.AvailableUpdate;

/**
 * Populates model attributes shared by every server-rendered page
 * (fragments/layout.html), so individual page controllers don't each need to
 * look them up. Scoped to the per-domain {@code *.infrastructure.web} packages
 * only - REST controllers under {@code *.infrastructure.rest} don't render a
 * Model/View and shouldn't pay for this lookup.
 */
@ControllerAdvice(basePackages = { "br.com.jorgemelo.nimbusfilemanager.conversion.infrastructure.web",
		"br.com.jorgemelo.nimbusfilemanager.database.infrastructure.web",
		"br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.web",
		"br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.web",
		"br.com.jorgemelo.nimbusfilemanager.geolocation.infrastructure.web",
		"br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.web",
		"br.com.jorgemelo.nimbusfilemanager.map.infrastructure.web",
		"br.com.jorgemelo.nimbusfilemanager.media.infrastructure.web",
		"br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.web",
		"br.com.jorgemelo.nimbusfilemanager.organization.infrastructure.web",
		"br.com.jorgemelo.nimbusfilemanager.quarantine.infrastructure.web",
		"br.com.jorgemelo.nimbusfilemanager.security.infrastructure.web",
		"br.com.jorgemelo.nimbusfilemanager.settings.infrastructure.web",
		"br.com.jorgemelo.nimbusfilemanager.statistics.infrastructure.web",
		"br.com.jorgemelo.nimbusfilemanager.timeline.infrastructure.web",
		"br.com.jorgemelo.nimbusfilemanager.update.infrastructure.web",
		"br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web" })
public class AppViewModelAdvice {

	private static final int DEFAULT_IDLE_TIMEOUT_MINUTES = 5;

	private final String appVersion;
	private final UserPagePreferenceService userPagePreferenceService;
	private final AppSettingService appSettingService;
	private final ExecutionQueryService executionQueryService;
	private final InventoryWatchService inventoryWatchService;
	private final AppUserRepository appUserRepository;
	private final FingerprintActivityService fingerprintActivityService;
	private final UpdateCheckService updateCheckService;

	@Autowired
	public AppViewModelAdvice(@Value("${application.version}") String appVersion,
			UserPagePreferenceService userPagePreferenceService, AppSettingService appSettingService,
			ExecutionQueryService executionQueryService, InventoryWatchService inventoryWatchService,
			AppUserRepository appUserRepository, FingerprintActivityService fingerprintActivityService,
			UpdateCheckService updateCheckService) {
		this.appVersion = appVersion;
		this.userPagePreferenceService = userPagePreferenceService;
		this.appSettingService = appSettingService;
		this.executionQueryService = executionQueryService;
		this.inventoryWatchService = inventoryWatchService;
		this.appUserRepository = appUserRepository;
		this.fingerprintActivityService = fingerprintActivityService;
		this.updateCheckService = updateCheckService;
	}

	@ModelAttribute("appVersion")
	public String appVersion() {
		return appVersion;
	}

	/**
	 * Read by the topbar on every screen, so a published version is visible
	 * without anyone opening the settings screen to look for it - which nobody
	 * does, because there is no reason to look for something you do not know
	 * exists.
	 */
	@ModelAttribute("availableUpdate")
	public String availableUpdate() {
		return updateCheckService.available().map(AvailableUpdate::published).orElse(null);
	}

	/**
	 * Read by fragments/layout.html into a data attribute on the shell, so
	 * pages/idle-timeout.js knows how long to wait before warning the user, without
	 * a dedicated fetch just for this.
	 */
	@ModelAttribute("idleTimeoutMinutes")
	public int idleTimeoutMinutes() {
		return appSettingService.intValue(SettingsConstants.IDLE_TIMEOUT_MINUTES, DEFAULT_IDLE_TIMEOUT_MINUTES);
	}

	@ModelAttribute("activeExecution")
	public ExecutionResponse activeExecution(Authentication authentication) {
		if (!isAuthenticated(authentication)) {
			return null;
		}

		return executionQueryService.active().orElse(null);
	}

	/**
	 * Background work that has no execution record of its own - the fingerprint
	 * backlogs - so the banner can show it instead of leaving the user guessing
	 * what is keeping the machine busy.
	 */
	@ModelAttribute("backgroundJob")
	public BackgroundJobActivity backgroundJob(Authentication authentication) {
		if (!isAuthenticated(authentication)) {
			return null;
		}

		return fingerprintActivityService.current().orElse(null);
	}

	@ModelAttribute("libraryConfigured")
	public boolean libraryConfigured() {
		return !appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "").isBlank();
	}

	@ModelAttribute("inventoryWatch")
	public InventoryWatchStatus inventoryWatch(Authentication authentication) {
		return isAuthenticated(authentication) ? inventoryWatchService.status() : InventoryWatchStatus.unconfigured();
	}

	/**
	 * Rendered directly into the initial HTML by fragments/layout.html so the
	 * sidebar opens in the user's last chosen state without a client-side
	 * fetch-then-toggle flash.
	 */
	@ModelAttribute("sidebarCollapsed")
	public boolean sidebarCollapsed(Authentication authentication) {
		if (!isAuthenticated(authentication)) {
			return false;
		}

		Map<String, String> preferences = userPagePreferenceService.find(authentication.getName(),
				SharedConstants.LAYOUT_PAGE_KEY);

		return Boolean.parseBoolean(preferences.get(SharedConstants.SIDEBAR_KEY));
	}

	/**
	 * Rendered by fragments/layout.html as a data-theme attribute on the shell, so
	 * the dark palette in css/base.css applies from the very first paint - no
	 * client-side toggle-after-load flash. Stored under the same "layout" page key
	 * as sidebarCollapsed since it's the same kind of cross-page UI preference;
	 * edited from the Preferencias tab of Configuracoes (SettingsWebController),
	 * same as sidebarCollapsed is from the sidebar toggle button.
	 */
	@ModelAttribute("theme")
	public String theme(Authentication authentication) {
		if (!isAuthenticated(authentication)) {
			return SharedConstants.THEME_LIGHT;
		}

		Map<String, String> preferences = userPagePreferenceService.find(authentication.getName(),
				SharedConstants.LAYOUT_PAGE_KEY);

		return SharedConstants.THEME_DARK.equals(preferences.get(SharedConstants.THEME_PREFERENCE_KEY))
				? SharedConstants.THEME_DARK
				: SharedConstants.THEME_LIGHT;
	}

	/**
	 * Rendered by fragments/layout.html into the sidebar profile card (avatar
	 * initials, display name, "Conta"/"Sair" links) so the sidebar doesn't need its
	 * own dedicated lookup - mirrors how AccountWebController resolves "user" for
	 * the account page itself.
	 */
	@ModelAttribute("currentUser")
	public AppUser currentUser(Authentication authentication) {
		if (!isAuthenticated(authentication)) {
			return null;
		}

		return appUserRepository.findByUsername(authentication.getName()).orElse(null);
	}

	/**
	 * Lets fragments/layout.html hide the "Administracao" nav group
	 * (Usuarios/Acessos/Configuracoes) for non-admin users, matching the
	 * server-side restriction in SecurityConfig - those pages 403 for USER role, so
	 * there's no point advertising them in the menu.
	 */
	@ModelAttribute("isAdmin")
	public boolean isAdmin(Authentication authentication) {
		if (!isAuthenticated(authentication)) {
			return false;
		}

		return authentication.getAuthorities().stream()
				.anyMatch(authority -> ("ROLE_" + Role.ADMIN.name()).equals(authority.getAuthority()));
	}

	private boolean isAuthenticated(Authentication authentication) {
		return authentication != null && authentication.isAuthenticated()
				&& !"anonymousUser".equals(authentication.getName());
	}
}