package br.com.jorgemelo.nimbusfilemanager.geolocation.infrastructure.web;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.GeoDatasetAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.LocationRebuildAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.MediaLocationService;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.OfflineGeoDataset;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.ReverseGeocodingStrategyRegistry;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants.GeolocationConstants;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationRebuildScope;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web.SettingsSectionModel;
import br.com.jorgemelo.nimbusfilemanager.shared.util.EnumUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SecurityUtils;

/**
 * Read-side assembler for the Geographic Database section of the settings page
 * (Sistema tab, admin): offline-dataset status, import/rebuild progress, cache
 * and location counters, plus the remembered rebuild scope. Extracted from the
 * settings controller so its render handler keeps a small constructor; the
 * matching write actions live in {@link SettingsGeodataWebController}.
 */
@Component
public class GeoDatasetSettingsModel implements SettingsSectionModel {

	private final OfflineGeoDataset offlineGeoDataset;
	private final MediaLocationService mediaLocationService;
	private final GeoDatasetAsyncRunner geoDatasetAsyncRunner;
	private final LocationRebuildAsyncRunner locationRebuildAsyncRunner;
	private final UserPagePreferenceService userPagePreferenceService;
	private final InventoryRunningState inventoryRunningState;
	private final ReverseGeocodingStrategyRegistry reverseGeocodingStrategyRegistry;

	@Autowired
	public GeoDatasetSettingsModel(OfflineGeoDataset offlineGeoDataset, MediaLocationService mediaLocationService,
			GeoDatasetAsyncRunner geoDatasetAsyncRunner, LocationRebuildAsyncRunner locationRebuildAsyncRunner,
			UserPagePreferenceService userPagePreferenceService, InventoryRunningState inventoryRunningState,
			ReverseGeocodingStrategyRegistry reverseGeocodingStrategyRegistry) {
		this.offlineGeoDataset = offlineGeoDataset;
		this.mediaLocationService = mediaLocationService;
		this.geoDatasetAsyncRunner = geoDatasetAsyncRunner;
		this.locationRebuildAsyncRunner = locationRebuildAsyncRunner;
		this.userPagePreferenceService = userPagePreferenceService;
		this.inventoryRunningState = inventoryRunningState;
		this.reverseGeocodingStrategyRegistry = reverseGeocodingStrategyRegistry;
	}

	public void addTo(Model model, Authentication authentication) {
		model.addAttribute("inventoryRunning", inventoryRunningState.isRunning());
		model.addAttribute("geoStatus", offlineGeoDataset.status());
		model.addAttribute("geoEnabled", mediaLocationService.enabled());
		model.addAttribute("geoImportRunning", geoDatasetAsyncRunner.isRunning());
		model.addAttribute("geoImportError", geoDatasetAsyncRunner.lastError());
		model.addAttribute("geoImportResult", geoDatasetAsyncRunner.lastResult());
		model.addAttribute("geoProgress", geoDatasetAsyncRunner.progress());
		model.addAttribute("geoCacheSize", mediaLocationService.cacheSize());
		model.addAttribute("geoResolvedCount", mediaLocationService.resolvedCount());
		model.addAttribute("geoPendingCount", mediaLocationService.pendingCount());
		model.addAttribute("geoRebuildRunning", locationRebuildAsyncRunner.isRunning());
		model.addAttribute("geoRebuildProcessed", locationRebuildAsyncRunner.processed());
		model.addAttribute("geoRebuildTotal", locationRebuildAsyncRunner.total());
		model.addAttribute("geoRebuildPercent", locationRebuildAsyncRunner.percent());
		model.addAttribute("geoRebuildEta", locationRebuildAsyncRunner.etaSeconds());
		model.addAttribute("geoRebuildError", locationRebuildAsyncRunner.lastError());
		model.addAttribute("geoRebuildResult", locationRebuildAsyncRunner.lastResult());
		model.addAttribute("geoRebuildScopes", LocationRebuildScope.values());

		// Feeds the provider dropdown of the location settings row: an implemented
		// provider is the only value that resolves anything, and a free-text field let
		// a typo through that silently fell back to the default.
		model.addAttribute("locationProviders", reverseGeocodingStrategyRegistry.available());

		Map<String, String> geoPreferences = userPagePreferenceService.find(username(authentication),
				GeolocationConstants.GEO_PAGE_KEY);

		model.addAttribute("geoRebuildScope",
				parseScope(geoPreferences.get(GeolocationConstants.GEO_REBUILD_SCOPE_KEY)).name());
	}

	private LocationRebuildScope parseScope(String value) {
		return EnumUtils.valueOfOrDefault(LocationRebuildScope.class, value, LocationRebuildScope.PENDING);
	}

	private String username(Authentication authentication) {
		return SecurityUtils.usernameOr(authentication, "system");
	}
}