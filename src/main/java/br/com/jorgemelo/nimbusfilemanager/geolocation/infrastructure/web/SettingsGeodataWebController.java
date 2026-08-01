package br.com.jorgemelo.nimbusfilemanager.geolocation.infrastructure.web;

import static br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants.GeolocationConstants.MESSAGE_BLOCKED;
import static br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants.GeolocationConstants.MESSAGE_WAIT_IMPORT;
import static br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants.GeolocationConstants.MESSAGE_WAIT_REBUILD;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.GeoDatasetAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.LocationRebuildAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.MediaLocationService;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.OfflineGeoDataset;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants.GeolocationConstants;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationRebuildScope;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SecurityUtils;

/**
 * Geographic Database administration actions on the Sistema tab (admin):
 * rebuild of resolved locations, offline-dataset download/removal and cache
 * clearing. Every action blocks while an inventory, an import or a rebuild is
 * running, because each of those reads or writes the boundary dataset or the
 * location cache and a concurrent change would corrupt work in flight. The
 * read-side model for this same section lives in
 * {@link GeoDatasetSettingsModel}.
 */
@Controller
public class SettingsGeodataWebController extends LocalizedComponent {

	private final UserPagePreferenceService userPagePreferenceService;
	private final OfflineGeoDataset offlineGeoDataset;
	private final MediaLocationService mediaLocationService;
	private final GeoDatasetAsyncRunner geoDatasetAsyncRunner;
	private final LocationRebuildAsyncRunner locationRebuildAsyncRunner;
	private final InventoryRunningState inventoryRunningState;
	private final AppSettingService appSettingService;

	@Autowired
	public SettingsGeodataWebController(UserPagePreferenceService userPagePreferenceService,
			OfflineGeoDataset offlineGeoDataset, MediaLocationService mediaLocationService,
			GeoDatasetAsyncRunner geoDatasetAsyncRunner, LocationRebuildAsyncRunner locationRebuildAsyncRunner,
			InventoryRunningState inventoryRunningState, AppSettingService appSettingService) {
		this.userPagePreferenceService = userPagePreferenceService;
		this.offlineGeoDataset = offlineGeoDataset;
		this.mediaLocationService = mediaLocationService;
		this.geoDatasetAsyncRunner = geoDatasetAsyncRunner;
		this.locationRebuildAsyncRunner = locationRebuildAsyncRunner;
		this.inventoryRunningState = inventoryRunningState;
		this.appSettingService = appSettingService;
	}

	@PostMapping("/app/settings/geodata/rebuild")
	public String rebuildLocations(@RequestParam(defaultValue = "PENDING") LocationRebuildScope scope,
			Authentication authentication, RedirectAttributes redirectAttributes) {
		// Remember the picked scope so the combo reopens on it, regardless of whether
		// the rebuild itself can start below.
		userPagePreferenceService.save(username(authentication), GeolocationConstants.GEO_PAGE_KEY,
				GeolocationConstants.GEO_REBUILD_SCOPE_KEY, scope.name());

		if (inventoryRunningState.isRunning()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR, message(MESSAGE_BLOCKED));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		if (geoDatasetAsyncRunner.isRunning()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR, message(MESSAGE_WAIT_IMPORT));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		if (!locationRebuildAsyncRunner.start(scope)) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR,
					message("backend.settings.rebuildRunning"));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		locationRebuildAsyncRunner.rebuild(scope);

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS, message("backend.settings.rebuildStarted"));

		return SharedConstants.REDIRECT_SETTINGS;
	}

	@PostMapping("/app/settings/geodata/download")
	public String downloadGeoDataset(RedirectAttributes redirectAttributes) {
		if (inventoryRunningState.isRunning()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR, message(MESSAGE_BLOCKED));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		// Replacing the boundary dataset mid-rebuild would pull the ground out from
		// under the running resolution, so the whole geo section waits for it.
		if (locationRebuildAsyncRunner.isRunning()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR, message(MESSAGE_WAIT_REBUILD));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		if (!geoDatasetAsyncRunner.start()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR,
					message("backend.settings.geoImportRunning"));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		geoDatasetAsyncRunner.downloadAndImport();

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS,
				message("backend.settings.geoImportStarted"));

		return SharedConstants.REDIRECT_SETTINGS;
	}

	/**
	 * Turns the feature on. The dataset itself is not requested here: the auto
	 * update notices it is missing and fetches it in the background, which is the
	 * whole point of not asking anyone to decide about a 2 GB download.
	 */
	@PostMapping("/app/settings/geodata/enable")
	public String enableLocation(Authentication authentication, RedirectAttributes redirectAttributes) {
		appSettingService.update(SettingsConstants.LOCATION_ENABLED, "true", username(authentication));

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS, message("backend.settings.geoEnabled"));

		return SharedConstants.REDIRECT_SETTINGS;
	}

	/**
	 * Turns the feature off, optionally reclaiming the disk it uses. The choice is
	 * asked at this moment because that is when it matters: the dataset is around
	 * 2 GB and someone who does not want the feature has no reason to keep it.
	 */
	@PostMapping("/app/settings/geodata/disable")
	public String disableLocation(@RequestParam(defaultValue = "false") boolean removeData,
			Authentication authentication, RedirectAttributes redirectAttributes) {
		if (geoDatasetAsyncRunner.isRunning()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR, message(MESSAGE_WAIT_IMPORT));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		if (locationRebuildAsyncRunner.isRunning()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR, message(MESSAGE_WAIT_REBUILD));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		appSettingService.update(SettingsConstants.LOCATION_ENABLED, "false", username(authentication));

		if (removeData) {
			offlineGeoDataset.remove();
		}

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS,
				message(removeData ? "backend.settings.geoDisabledAndRemoved" : "backend.settings.geoDisabled"));

		return SharedConstants.REDIRECT_SETTINGS;
	}

	@PostMapping("/app/settings/geodata/remove")
	public String removeGeoDataset(RedirectAttributes redirectAttributes) {
		if (inventoryRunningState.isRunning()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR, message(MESSAGE_BLOCKED));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		// Removing the boundaries a rebuild is actively reading would break it.
		if (locationRebuildAsyncRunner.isRunning()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR, message(MESSAGE_WAIT_REBUILD));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		if (geoDatasetAsyncRunner.isRunning()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR,
					message("backend.settings.waitRunningImport"));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		offlineGeoDataset.remove();

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS, message("backend.settings.geoRemoved"));

		return SharedConstants.REDIRECT_SETTINGS;
	}

	@PostMapping("/app/settings/geodata/clear-cache")
	public String clearGeoCache(RedirectAttributes redirectAttributes) {
		// The cache feeds - and is written by - inventory, import and rebuild, so
		// clearing it mid-operation would undo work in flight. Block like the rest
		// of the geo section instead of silently racing.
		if (inventoryRunningState.isRunning()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR, message(MESSAGE_BLOCKED));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		if (locationRebuildAsyncRunner.isRunning()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR, message(MESSAGE_WAIT_REBUILD));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		if (geoDatasetAsyncRunner.isRunning()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR, message(MESSAGE_WAIT_IMPORT));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		long removed = mediaLocationService.clearCache();

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS,
				message("backend.settings.cacheCleared", removed));

		return SharedConstants.REDIRECT_SETTINGS;
	}

	private String username(Authentication authentication) {
		return SecurityUtils.usernameOr(authentication, "system");
	}
}