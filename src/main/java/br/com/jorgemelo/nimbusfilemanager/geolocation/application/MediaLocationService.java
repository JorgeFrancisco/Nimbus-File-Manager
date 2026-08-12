package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Coordinates;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LocationResolution;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.GeoResolutionCache;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.MediaGeoLocation;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.ResolvedPlace;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoResolutionCacheRepository;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.MediaGeoLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;

/**
 * Global facade for resolved media locations. Everything in the application
 * (inventory, organization, timeline, details) talks to this service; only this
 * package knows strategies, caches and datasets. Resolutions are persisted once
 * as media_geo_location and reused - never recalculated by screens.
 */
@Service
public class MediaLocationService {

	private final ReverseGeocodingStrategyRegistry registry;
	private final MediaGeoLocationRepository mediaGeoLocationRepository;
	private final GeoResolutionCacheRepository cacheRepository;
	private final AppSettingService appSettingService;
	private final Clock clock;

	public MediaLocationService(ReverseGeocodingStrategyRegistry registry,
			MediaGeoLocationRepository mediaGeoLocationRepository, GeoResolutionCacheRepository cacheRepository,
			AppSettingService appSettingService, Clock clock) {
		this.registry = registry;
		this.mediaGeoLocationRepository = mediaGeoLocationRepository;
		this.cacheRepository = cacheRepository;
		this.appSettingService = appSettingService;
		this.clock = clock;
	}

	public boolean enabled() {
		return appSettingService.booleanValue(SettingsConstants.LOCATION_ENABLED, false);
	}

	/**
	 * Resolves coordinates through the persistent cache and the configured
	 * strategy. Returns empty when the feature can't resolve (no strategy, dataset
	 * unavailable, nothing near the coordinates).
	 */
	@Transactional
	public Optional<LocationResolution> resolve(Coordinates coordinates) {
		return doResolve(coordinates);
	}

	private Optional<LocationResolution> doResolve(Coordinates coordinates) {
		if (coordinates == null) {
			return Optional.empty();
		}

		Optional<ReverseGeocodingStrategy> strategy = registry.configured()
				.filter(ReverseGeocodingStrategy::isAvailable);

		if (strategy.isEmpty()) {
			return Optional.empty();
		}

		String cacheKey = cacheKey(strategy.get(), coordinates);

		Optional<GeoResolutionCache> cached = cacheRepository.findByCacheKey(cacheKey);

		if (cached.isPresent()) {
			return Optional.of(toResolution(cached.get()));
		}

		Optional<LocationResolution> resolved = strategy.get().resolve(coordinates);

		resolved.ifPresent(resolution -> saveCache(cacheKey, resolution));

		return resolved;
	}

	/**
	 * Resolves and persists the location of a media. Returns true when a
	 * resolution was persisted.
	 */
	@Transactional
	public boolean resolveAndPersist(Long catalogFileId, Coordinates coordinates) {
		return doResolveAndPersist(catalogFileId, coordinates);
	}

	private boolean doResolveAndPersist(Long catalogFileId, Coordinates coordinates) {
		if (catalogFileId == null || coordinates == null) {
			return false;
		}

		Optional<MediaGeoLocation> existing = mediaGeoLocationRepository.findById(catalogFileId);

		Optional<LocationResolution> resolution = doResolve(coordinates);

		if (resolution.isEmpty()) {
			return false;
		}

		MediaGeoLocation entity = existing.orElseGet(MediaGeoLocation::new);

		if (entity.getId() == null) {
			entity.setId(catalogFileId);
		}

		apply(entity, resolution.get());

		mediaGeoLocationRepository.save(entity);

		return true;
	}

	/**
	 * Inventory-time variant: resolves only when the media has no persisted
	 * location yet, so re-scans never redo work (rebuild handles refreshes).
	 */
	@Transactional
	public boolean resolveIfAbsent(Long catalogFileId, Coordinates coordinates) {
		if (catalogFileId == null || coordinates == null) {
			return false;
		}

		if (mediaGeoLocationRepository.existsById(catalogFileId)) {
			return false;
		}

		return doResolveAndPersist(catalogFileId, coordinates);
	}

	@Transactional(readOnly = true)
	public Optional<MediaGeoLocation> locationOf(Long catalogFileId) {
		return catalogFileId == null ? Optional.empty() : mediaGeoLocationRepository.findById(catalogFileId);
	}

	@Transactional(readOnly = true)
	public Map<Long, MediaGeoLocation> locationsOf(List<Long> catalogFileIds) {
		if (catalogFileIds == null || catalogFileIds.isEmpty()) {
			return Map.of();
		}

		return mediaGeoLocationRepository.findByIdIn(catalogFileIds.toArray(Long[]::new)).stream()
				.collect(Collectors.toMap(MediaGeoLocation::getId, Function.identity()));
	}

	@Transactional
	public long clearCache() {
		long count = cacheRepository.count();

		cacheRepository.deleteAllInBatch();

		return count;
	}

	@Transactional(readOnly = true)
	public long cacheSize() {
		return cacheRepository.count();
	}

	@Transactional(readOnly = true)
	public long resolvedCount() {
		return mediaGeoLocationRepository.count();
	}

	@Transactional(readOnly = true)
	public long pendingCount() {
		return mediaGeoLocationRepository.countPending();
	}

	private String cacheKey(ReverseGeocodingStrategy strategy, Coordinates coordinates) {
		return strategy.provider().name() + ":" + coordinates.roundedKey();
	}

	private void saveCache(String cacheKey, LocationResolution resolution) {
		// Idempotent by design (ON CONFLICT DO NOTHING): a concurrent duplicate is a
		// no-op, never an exception, so it can't poison the surrounding transaction.
		cacheRepository.insertIgnoringDuplicate(GeoResolutionCache.builder().cacheKey(cacheKey)
				.place(ResolvedPlace.builder().countryCode(resolution.countryCode())
						.countryName(resolution.countryName()).stateName(resolution.stateName())
						.cityName(resolution.cityName()).distanceKm(resolution.distanceKm())
						.confidence(resolution.confidence()).provider(resolution.provider())
						.datasetVersion(resolution.datasetVersion()).resolvedAt(resolution.resolvedAt())
						.openSea(resolution.openSea()).build())
				.build());
	}

	private void apply(MediaGeoLocation entity, LocationResolution resolution) {
		entity.setPlace(ResolvedPlace.builder().countryCode(resolution.countryCode())
				.countryName(resolution.countryName()).stateName(resolution.stateName()).cityName(resolution.cityName())
				.distanceKm(resolution.distanceKm()).confidence(resolution.confidence()).provider(resolution.provider())
				.datasetVersion(resolution.datasetVersion()).openSea(resolution.openSea())
				.resolvedAt(resolution.resolvedAt() == null ? LocalDateTime.now(clock) : resolution.resolvedAt())
				.build());
	}

	private LocationResolution toResolution(GeoResolutionCache cache) {
		ResolvedPlace place = cache.getPlace();

		return new LocationResolution(place.getCountryCode(), place.getCountryName(), place.getStateName(),
				place.getCityName(), place.getDistanceKm(), place.getConfidence(), place.getProvider(),
				place.getDatasetVersion(), place.getResolvedAt(), place.isOpenSea());
	}
}