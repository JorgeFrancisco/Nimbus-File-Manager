package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Coordinates;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LocationResolution;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationProvider;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.GeoResolutionCache;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.ResolvedPlace;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoResolutionCacheRepository;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.MediaGeoLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;

@ExtendWith(MockitoExtension.class)
class MediaLocationServiceTest {

	@Mock
	ReverseGeocodingStrategyRegistry registry;
	@Mock
	MediaGeoLocationRepository locationRepository;
	@Mock
	GeoResolutionCacheRepository cacheRepository;
	@Mock
	AppSettingService settings;
	@Mock
	ReverseGeocodingStrategy strategy;

	@Test
	void shouldReusePersistentCacheWithoutCallingProvider() {
		Coordinates coordinates = new Coordinates(-25.4284, -49.2733);

		when(registry.configured()).thenReturn(Optional.of(strategy));
		when(strategy.isAvailable()).thenReturn(true);
		when(strategy.provider()).thenReturn(LocationProvider.ADMIN_BOUNDARIES);
		when(cacheRepository.findByCacheKey("ADMIN_BOUNDARIES:-25.4284:-49.2733"))
				.thenReturn(Optional.of(cacheEntry()));

		Assertions.assertThat(service().resolve(coordinates)).get().extracting(LocationResolution::cityName)
				.isEqualTo("Curitiba");

		verify(strategy, never()).resolve(coordinates);
	}

	private MediaLocationService service() {
		return new MediaLocationService(registry, locationRepository, cacheRepository, settings,
				Clock.systemDefaultZone());
	}

	private GeoResolutionCache cacheEntry() {
		return GeoResolutionCache.builder().cacheKey("key")
				.place(ResolvedPlace.builder().countryCode("BR").countryName("Brasil").stateName("Paraná")
						.cityName("Curitiba").distanceKm(2.4).confidence(LocationConfidence.VERY_HIGH)
						.provider(LocationProvider.ADMIN_BOUNDARIES).datasetVersion("2026-07-11")
						.resolvedAt(LocalDateTime.now()).build())
				.build();
	}
}