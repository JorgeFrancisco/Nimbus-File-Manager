package br.com.jorgemelo.nimbusfilemanager.geolocation.infrastructure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.ui.ExtendedModelMap;

import br.com.jorgemelo.nimbusfilemanager.execution.application.EtaLabels;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.GeoRunReader;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.MediaLocationService;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.OfflineGeoDataset;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.ReverseGeocodingStrategyRegistry;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants.GeolocationConstants;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

class GeoDatasetSettingsModelTest {

	private final OfflineGeoDataset offlineGeoDataset = mock(OfflineGeoDataset.class);
	private final MediaLocationService mediaLocationService = mock(MediaLocationService.class);
	private final GeoRunReader geoRunReader = mock(GeoRunReader.class);
	private final UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final InventoryRunningState inventoryRunningState = new InventoryRunningState(executionRepository);
	private final ReverseGeocodingStrategyRegistry reverseGeocodingStrategyRegistry = mock(
			ReverseGeocodingStrategyRegistry.class);
	private final GeoDatasetSettingsModel model = new GeoDatasetSettingsModel(mock(EtaLabels.class), offlineGeoDataset,
			mediaLocationService,
			geoRunReader, preferences, inventoryRunningState,
			reverseGeocodingStrategyRegistry);
	private final TestingAuthenticationToken authentication = new TestingAuthenticationToken("Admin@Example.com", "pw");

	@Test
	void shouldPreselectTheSavedLocationRebuildScope() {
		when(preferences.find("Admin@Example.com", GeolocationConstants.GEO_PAGE_KEY))
				.thenReturn(Map.of(GeolocationConstants.GEO_REBUILD_SCOPE_KEY, "ALL"));

		ExtendedModelMap view = new ExtendedModelMap();

		model.addTo(view, authentication);

		Assertions.assertThat(view).containsEntry("geoRebuildScope", "ALL");
	}

	@Test
	void shouldFallBackToPendingWhenSavedRebuildScopeIsCorrupted() {
		when(preferences.find("Admin@Example.com", GeolocationConstants.GEO_PAGE_KEY))
				.thenReturn(Map.of(GeolocationConstants.GEO_REBUILD_SCOPE_KEY, "NOT_A_REAL_SCOPE"));

		ExtendedModelMap view = new ExtendedModelMap();

		model.addTo(view, authentication);

		Assertions.assertThat(view).containsEntry("geoRebuildScope", "PENDING");
	}
}