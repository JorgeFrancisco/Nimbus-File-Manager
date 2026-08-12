package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Coordinates;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LocationResolution;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationProvider;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.GeoResolutionCache;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.MediaGeoLocation;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.ResolvedPlace;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoResolutionCacheRepository;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.MediaGeoLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;

/**
 * Resolution, persistence and bookkeeping paths of the location facade not
 * covered by {@link MediaLocationServiceTest}: cache miss + provider call,
 * persistence of new/absent locations and the counting helpers.
 */
class MediaLocationServiceResolveTest {

	private final ReverseGeocodingStrategyRegistry registry = mock(ReverseGeocodingStrategyRegistry.class);
	private final MediaGeoLocationRepository locationRepository = mock(MediaGeoLocationRepository.class);
	private final GeoResolutionCacheRepository cacheRepository = mock(GeoResolutionCacheRepository.class);
	private final AppSettingService settings = mock(AppSettingService.class);
	private final ReverseGeocodingStrategy strategy = mock(ReverseGeocodingStrategy.class);

	private final MediaLocationService service = new MediaLocationService(registry, locationRepository, cacheRepository,
			settings, Clock.systemDefaultZone());

	private final Coordinates coordinates = new Coordinates(-25.4284, -49.2733);

	@Test
	void enabledReadsSetting() {
		when(settings.booleanValue(SettingsConstants.LOCATION_ENABLED, false)).thenReturn(true);

		Assertions.assertThat(service.enabled()).isTrue();
	}

	@Test
	void resolveReturnsEmptyForNullCoordinates() {
		Assertions.assertThat(service.resolve(null)).isEmpty();
	}

	@Test
	void resolveReturnsEmptyWhenNoStrategyConfigured() {
		when(registry.configured()).thenReturn(Optional.empty());

		Assertions.assertThat(service.resolve(coordinates)).isEmpty();
	}

	@Test
	void resolveReturnsEmptyWhenStrategyUnavailable() {
		when(registry.configured()).thenReturn(Optional.of(strategy));
		when(strategy.isAvailable()).thenReturn(false);

		Assertions.assertThat(service.resolve(coordinates)).isEmpty();
	}

	@Test
	void resolveCallsProviderOnCacheMissAndStoresResult() {
		configuredStrategy();
		when(cacheRepository.findByCacheKey(anyString())).thenReturn(Optional.empty());
		when(strategy.resolve(coordinates)).thenReturn(Optional.of(resolution()));

		Assertions.assertThat(service.resolve(coordinates)).get().extracting(LocationResolution::cityName)
				.isEqualTo("Curitiba");

		verify(cacheRepository).insertIgnoringDuplicate(any(GeoResolutionCache.class));
	}

	@Test
	void resolveReturnsEmptyWhenStrategyCannotResolve() {
		configuredStrategy();

		when(cacheRepository.findByCacheKey(anyString())).thenReturn(Optional.empty());
		when(strategy.resolve(coordinates)).thenReturn(Optional.empty());

		Assertions.assertThat(service.resolve(coordinates)).isEmpty();

		verify(cacheRepository, never()).insertIgnoringDuplicate(any());
	}

	@Test
	void resolveSucceedsWhenAConcurrentThreadAlreadyCachedTheKey() {
		configuredStrategy();

		when(cacheRepository.findByCacheKey(anyString())).thenReturn(Optional.empty());
		when(strategy.resolve(coordinates)).thenReturn(Optional.of(resolution()));
		when(cacheRepository.insertIgnoringDuplicate(any())).thenReturn(0);

		Assertions.assertThat(service.resolve(coordinates)).isPresent();
	}

	@Test
	void resolveAndPersistPersistsNewResolution() {
		when(locationRepository.findById(9L)).thenReturn(Optional.empty());

		configuredStrategy();

		when(cacheRepository.findByCacheKey(anyString())).thenReturn(Optional.of(cacheEntry()));

		Assertions.assertThat(service.resolveAndPersist(9L, coordinates)).isTrue();

		verify(locationRepository).save(any(MediaGeoLocation.class));
	}

	@Test
	void resolveAndPersistReturnsFalseWhenUnresolved() {
		when(locationRepository.findById(9L)).thenReturn(Optional.empty());
		when(registry.configured()).thenReturn(Optional.empty());

		Assertions.assertThat(service.resolveAndPersist(9L, coordinates)).isFalse();

		verify(locationRepository, never()).save(any());
	}

	@Test
	void resolveAndPersistReturnsFalseForNullArguments() {
		Assertions.assertThat(service.resolveAndPersist(null, coordinates)).isFalse();
		Assertions.assertThat(service.resolveAndPersist(9L, null)).isFalse();
	}

	@Test
	void resolveIfAbsentSkipsWhenLocationAlreadyExists() {
		when(locationRepository.existsById(9L)).thenReturn(true);

		Assertions.assertThat(service.resolveIfAbsent(9L, coordinates)).isFalse();

		verify(locationRepository, never()).findById(any());
	}

	@Test
	void resolveIfAbsentResolvesWhenLocationIsAbsent() {
		when(locationRepository.existsById(9L)).thenReturn(false);
		when(locationRepository.findById(9L)).thenReturn(Optional.empty());

		configuredStrategy();

		when(cacheRepository.findByCacheKey(anyString())).thenReturn(Optional.of(cacheEntry()));

		Assertions.assertThat(service.resolveIfAbsent(9L, coordinates)).isTrue();
	}

	@Test
	void resolveIfAbsentReturnsFalseForNullArguments() {
		Assertions.assertThat(service.resolveIfAbsent(null, coordinates)).isFalse();
		Assertions.assertThat(service.resolveIfAbsent(9L, null)).isFalse();
	}

	@Test
	void locationOfDelegatesAndHandlesNull() {
		MediaGeoLocation location = MediaGeoLocation.builder().id(9L).build();

		when(locationRepository.findById(9L)).thenReturn(Optional.of(location));

		Assertions.assertThat(service.locationOf(9L)).contains(location);
		Assertions.assertThat(service.locationOf(null)).isEmpty();
	}

	@Test
	void locationsOfMapsByIdAndHandlesEmptyInput() {
		MediaGeoLocation a = MediaGeoLocation.builder().id(1L).build();
		MediaGeoLocation b = MediaGeoLocation.builder().id(2L).build();

		when(locationRepository.findByIdIn(new Long[] { 1L, 2L })).thenReturn(List.of(a, b));

		Map<Long, MediaGeoLocation> map = service.locationsOf(List.of(1L, 2L));

		Assertions.assertThat(map).containsEntry(1L, a).containsEntry(2L, b);
		Assertions.assertThat(service.locationsOf(List.of())).isEmpty();
		Assertions.assertThat(service.locationsOf(null)).isEmpty();
	}

	@Test
	void countingHelpersDelegateToRepositories() {
		when(cacheRepository.count()).thenReturn(4L);
		when(locationRepository.count()).thenReturn(11L);
		when(locationRepository.countPending()).thenReturn(6L);

		Assertions.assertThat(service.cacheSize()).isEqualTo(4);
		Assertions.assertThat(service.resolvedCount()).isEqualTo(11);
		Assertions.assertThat(service.pendingCount()).isEqualTo(6);
	}

	@Test
	void clearCacheReturnsPreviousSizeAndEmptiesCache() {
		when(cacheRepository.count()).thenReturn(7L);

		Assertions.assertThat(service.clearCache()).isEqualTo(7);

		verify(cacheRepository).deleteAllInBatch();
	}

	private void configuredStrategy() {
		when(registry.configured()).thenReturn(Optional.of(strategy));
		when(strategy.isAvailable()).thenReturn(true);
		when(strategy.provider()).thenReturn(LocationProvider.ADMIN_BOUNDARIES);
	}

	private LocationResolution resolution() {
		return new LocationResolution("BR", "Brasil", "Paraná", "Curitiba", 2.4, LocationConfidence.VERY_HIGH,
				LocationProvider.ADMIN_BOUNDARIES, "2026-07-11", LocalDateTime.now(), false);
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