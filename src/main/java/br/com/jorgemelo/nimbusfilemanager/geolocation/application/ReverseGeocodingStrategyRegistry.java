package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationProvider;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;

/**
 * Locates the configured {@link ReverseGeocodingStrategy} without scattering
 * provider conditionals around the project. Every strategy Spring bean is
 * indexed by its provider; the active one comes from settings.
 */
@Component
public class ReverseGeocodingStrategyRegistry {

	private final Map<LocationProvider, ReverseGeocodingStrategy> strategies;
	private final AppSettingService appSettingService;

	public ReverseGeocodingStrategyRegistry(List<ReverseGeocodingStrategy> strategies,
			AppSettingService appSettingService) {
		this.strategies = strategies.stream()
				.collect(Collectors.toUnmodifiableMap(ReverseGeocodingStrategy::provider, Function.identity()));
		this.appSettingService = appSettingService;
	}

	public Optional<ReverseGeocodingStrategy> configured() {
		return strategyFor(configuredProvider());
	}

	/**
	 * Providers that actually have a strategy behind them, in enum order, so the
	 * settings screen offers only what the application can resolve with - the
	 * remaining {@link LocationProvider} values name where a location came from,
	 * not something that can be picked.
	 */
	public List<LocationProvider> available() {
		return Arrays.stream(LocationProvider.values()).filter(strategies::containsKey).toList();
	}

	Optional<ReverseGeocodingStrategy> strategyFor(LocationProvider provider) {
		return Optional.ofNullable(strategies.get(provider));
	}

	LocationProvider configuredProvider() {
		String configured = appSettingService.stringValue(SettingsConstants.LOCATION_PROVIDER,
				LocationProvider.ADMIN_BOUNDARIES.name());

		try {
			return LocationProvider.valueOf(configured.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException _) {
			return LocationProvider.ADMIN_BOUNDARIES;
		}
	}
}