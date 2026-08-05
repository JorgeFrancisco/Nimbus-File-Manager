package br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository;

import java.time.LocalDateTime;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationProvider;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.GeoResolutionCache;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.ResolvedPlace;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;

/**
 * Runtime check that
 * {@link GeoResolutionCacheRepository#insertIgnoringDuplicate} behaves against
 * a real PostgreSQL instance: it leans on the Postgres-only
 * {@code ON CONFLICT DO NOTHING} to make a concurrent duplicate a no-op instead
 * of a unique-constraint violation that would abort the whole transaction
 * (SQLState 25P02), which is exactly what broke the parallel location rebuild.
 * The SpEL binding of the embedded {@link ResolvedPlace} (enums by name) is
 * also exercised end to end here.
 */
class GeoResolutionCacheRepositoryIntegrationTest extends SharedPostgresIntegrationTest {

	@Autowired
	private GeoResolutionCacheRepository repository;

	@Test
	void insertIgnoringDuplicateWritesOnceAndTreatsAConcurrentDuplicateAsANoOp() {
		String cacheKey = "ADMIN_BOUNDARIES:12.4143:-69.8808";

		int first = repository.insertIgnoringDuplicate(entry(cacheKey));
		int second = repository.insertIgnoringDuplicate(entry(cacheKey));

		Assertions.assertThat(first).isEqualTo(1);
		Assertions.assertThat(second).isZero();

		Assertions.assertThat(repository.count()).isEqualTo(1);
		Assertions.assertThat(repository.findByCacheKey(cacheKey)).get()
				.extracting(cache -> cache.getPlace().getCityName(), cache -> cache.getPlace().getConfidence(),
						cache -> cache.getPlace().getProvider())
				.containsExactly("Oranjestad", LocationConfidence.HIGH, LocationProvider.ADMIN_BOUNDARIES);
	}

	private GeoResolutionCache entry(String cacheKey) {
		return GeoResolutionCache.builder().cacheKey(cacheKey)
				.place(ResolvedPlace.builder().countryCode("AW").countryName("Aruba").cityName("Oranjestad")
						.distanceKm(1.5).confidence(LocationConfidence.HIGH).provider(LocationProvider.ADMIN_BOUNDARIES)
						.datasetVersion("2026-07-11").resolvedAt(LocalDateTime.now()).build())
				.build();
	}
}