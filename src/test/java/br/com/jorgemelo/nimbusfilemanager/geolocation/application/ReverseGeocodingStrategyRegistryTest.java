package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationProvider;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;

/**
 * The registry indexes the reverse-geocoding strategy beans by provider and
 * resolves the active one from settings. These checks pin the observable
 * contract: an unknown/garbage setting must degrade to ADMIN_BOUNDARIES rather
 * than blow up, and a provider with no registered bean must yield an empty
 * Optional (never null / never a wrong strategy).
 */
class ReverseGeocodingStrategyRegistryTest {

	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final ReverseGeocodingStrategy adminStrategy = mock(ReverseGeocodingStrategy.class);

	private ReverseGeocodingStrategyRegistry registry() {
		when(adminStrategy.provider()).thenReturn(LocationProvider.ADMIN_BOUNDARIES);

		return new ReverseGeocodingStrategyRegistry(List.of(adminStrategy), appSettingService);
	}

	private void configuredSettingIs(String value) {
		when(appSettingService.stringValue(SettingsConstants.LOCATION_PROVIDER,
				LocationProvider.ADMIN_BOUNDARIES.name())).thenReturn(value);
	}

	@Test
	void configuredProviderParsesTheSettingCaseInsensitively() {
		configuredSettingIs("  google_maps  ");

		Assertions.assertThat(registry().configuredProvider()).isEqualTo(LocationProvider.GOOGLE_MAPS);
	}

	@Test
	void configuredProviderFallsBackToAdminBoundariesWhenSettingIsGarbage() {
		configuredSettingIs("not-a-real-provider");

		Assertions.assertThat(registry().configuredProvider()).isEqualTo(LocationProvider.ADMIN_BOUNDARIES);
	}

	@Test
	void configuredReturnsTheRegisteredStrategyForTheActiveProvider() {
		configuredSettingIs(LocationProvider.ADMIN_BOUNDARIES.name());

		Assertions.assertThat(registry().configured()).containsSame(adminStrategy);
	}

	@Test
	void configuredIsEmptyWhenTheActiveProviderHasNoRegisteredStrategy() {
		configuredSettingIs(LocationProvider.OPENSTREETMAP.name());

		Assertions.assertThat(registry().configured()).isEmpty();
	}

	@Test
	void strategyForReturnsEmptyForAnUnregisteredProvider() {
		Assertions.assertThat(registry().strategyFor(LocationProvider.MANUAL)).isEmpty();
	}

	/**
	 * Feeds the provider dropdown on the settings screen, so it must offer only
	 * what a strategy backs: MANUAL and UNKNOWN say where a location came from and
	 * resolve nothing, and offering them would be offering a no-op.
	 */
	@Test
	void availableListsOnlyTheProvidersThatHaveAStrategy() {
		Assertions.assertThat(registry().available()).containsExactly(LocationProvider.ADMIN_BOUNDARIES);
	}
}