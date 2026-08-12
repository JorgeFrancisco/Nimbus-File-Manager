package br.com.jorgemelo.nimbusfilemanager.geolocation.infrastructure.web;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import br.com.jorgemelo.nimbusfilemanager.execution.application.EtaLabels;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.GeoRunReader;
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
	private final GeoRunReader geoRunReader;
	private final EtaLabels etaLabels;
	private final UserPagePreferenceService userPagePreferenceService;
	private final InventoryRunningState inventoryRunningState;
	private final ReverseGeocodingStrategyRegistry reverseGeocodingStrategyRegistry;

	@Autowired
	public GeoDatasetSettingsModel(EtaLabels etaLabels, OfflineGeoDataset offlineGeoDataset,
			MediaLocationService mediaLocationService,
			GeoRunReader geoRunReader, UserPagePreferenceService userPagePreferenceService,
			InventoryRunningState inventoryRunningState,
			ReverseGeocodingStrategyRegistry reverseGeocodingStrategyRegistry) {
		this.offlineGeoDataset = offlineGeoDataset;
		this.mediaLocationService = mediaLocationService;
		this.geoRunReader = geoRunReader;
		this.etaLabels = etaLabels;
		this.userPagePreferenceService = userPagePreferenceService;
		this.inventoryRunningState = inventoryRunningState;
		this.reverseGeocodingStrategyRegistry = reverseGeocodingStrategyRegistry;
	}

	public void addTo(Model model, Authentication authentication) {
		model.addAttribute("inventoryRunning", inventoryRunningState.isRunning());
		model.addAttribute("geoStatus", offlineGeoDataset.status());
		model.addAttribute("geoEnabled", mediaLocationService.enabled());
		model.addAttribute("geoImportRunning", geoRunReader.importRunning());
		// When the dataset was last checked against its source, which a run that
		// imported nothing still answers - and the dataset itself cannot.
		model.addAttribute("geoVerifiedAt", geoRunReader.lastVerifiedAt());
		model.addAttribute("geoImportError", geoRunReader.importError());
		model.addAttribute("geoProgress", geoRunReader.progress());
		model.addAttribute("geoCacheSize", mediaLocationService.cacheSize());
		model.addAttribute("geoResolvedCount", mediaLocationService.resolvedCount());
		model.addAttribute("geoPendingCount", mediaLocationService.pendingCount());
		model.addAttribute("geoRebuildRunning", geoRunReader.rebuildRunning());
		model.addAttribute("geoRebuildProcessed", geoRunReader.rebuildProcessed());
		model.addAttribute("geoRebuildTotal", geoRunReader.rebuildTotal());
		model.addAttribute("geoRebuildPercent", geoRunReader.rebuildPercent());
		model.addAttribute("geoRebuildEta", etaLabels.label(geoRunReader.rebuildEta()));
		model.addAttribute("geoRebuildError", geoRunReader.rebuildError());
		model.addAttribute("geoRebuildResult", geoRunReader.lastRebuildResult());
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