package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LocationRebuildResult;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationRebuildScope;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.MediaGeoLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.projection.MediaCoordinatesProjection;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.LocationRebuildProperties;

/**
 * Batch rebuild of resolved locations: per-scope counting/querying, keyset
 * pagination, outcome accounting (resolved/unresolved/errors) and resilience to
 * per-media failures.
 */
class LocationRebuildServiceTest {

	private final MediaGeoLocationRepository repository = mock(MediaGeoLocationRepository.class);
	private final MediaLocationService mediaLocationService = mock(MediaLocationService.class);
	private final LocationRebuildService service = new LocationRebuildService(repository, mediaLocationService,
			new LocationRebuildProperties());

	@Test
	void countCandidatesDelegatesPerScope() {
		when(repository.countPending()).thenReturn(3L);
		when(repository.countByConfidenceForRebuild(any())).thenReturn(2L);
		when(repository.countAllResolvable()).thenReturn(5L);

		Assertions.assertThat(service.countCandidates(LocationRebuildScope.PENDING)).isEqualTo(3);
		Assertions.assertThat(service.countCandidates(LocationRebuildScope.LOW_CONFIDENCE)).isEqualTo(2);
		Assertions.assertThat(service.countCandidates(LocationRebuildScope.ALL)).isEqualTo(5);
	}

	@Test
	void rebuildResolvesAndCountsOutcomes() {
		when(repository.findAllResolvableIds(eq(0L), any())).thenReturn(List.of(1L, 2L));
		when(repository.findAllResolvableIds(eq(2L), any())).thenReturn(List.of());
		when(repository.findCoordinatesByIds(any()))
				.thenReturn(List.of(projection(1L, -25.0, -49.0), projection(2L, -26.0, -50.0)));
		when(mediaLocationService.resolveAndPersist(eq(1L), any())).thenReturn(true);
		when(mediaLocationService.resolveAndPersist(eq(2L), any())).thenReturn(false);

		AtomicLong lastProgress = new AtomicLong();

		LocationRebuildResult result = service.rebuild(LocationRebuildScope.ALL, lastProgress::set);

		Assertions.assertThat(result.scope()).isEqualTo(LocationRebuildScope.ALL);
		Assertions.assertThat(result.candidates()).isEqualTo(2);
		Assertions.assertThat(result.resolved()).isEqualTo(1);
		Assertions.assertThat(result.unresolved()).isEqualTo(1);
		Assertions.assertThat(result.errors()).isZero();
		Assertions.assertThat(lastProgress.get()).isEqualTo(2);
	}

	@Test
	void rebuildResolvesEveryMediaWhenProcessingConcurrently() {
		List<Long> ids = new ArrayList<>();
		List<MediaCoordinatesProjection> projections = new ArrayList<>();

		for (long id = 1; id <= 50; id++) {
			ids.add(id);
			projections.add(projection(id, -25.0, -49.0));
		}

		when(repository.findAllResolvableIds(eq(0L), any())).thenReturn(ids);
		when(repository.findAllResolvableIds(eq(50L), any())).thenReturn(List.of());
		when(repository.findCoordinatesByIds(any())).thenReturn(projections);
		when(mediaLocationService.resolveAndPersist(any(), any())).thenReturn(true);

		AtomicLong lastProgress = new AtomicLong();

		LocationRebuildResult result = service.rebuild(LocationRebuildScope.ALL, lastProgress::set);

		Assertions.assertThat(result.candidates()).isEqualTo(50);
		Assertions.assertThat(result.resolved()).isEqualTo(50);
		Assertions.assertThat(result.unresolved()).isZero();
		Assertions.assertThat(result.errors()).isZero();
		Assertions.assertThat(lastProgress.get()).isEqualTo(50);
	}

	@Test
	void rebuildPaginatesAcrossManyBatchesAndReportsCumulativeProgress() {
		// More than one keyset page and past the progress heartbeat threshold: the
		// running total must stay correct across batches processed in parallel.
		long totalMedia = 10_500;

		when(repository.findAllResolvableIds(anyLong(), any())).thenAnswer(invocation -> {
			long lastId = invocation.getArgument(0);

			if (lastId >= totalMedia) {
				return List.of();
			}

			List<Long> batch = new ArrayList<>();

			for (long id = lastId + 1; id <= Math.min(lastId + 500, totalMedia); id++) {
				batch.add(id);
			}

			return batch;
		});
		when(repository.findCoordinatesByIds(any())).thenAnswer(invocation -> {
			List<Long> ids = invocation.getArgument(0);
			List<MediaCoordinatesProjection> projections = new ArrayList<>();

			for (Long id : ids) {
				projections.add(projection(id, -25.0, -49.0));
			}

			return projections;
		});
		when(mediaLocationService.resolveAndPersist(any(), any())).thenReturn(true);

		AtomicLong lastProgress = new AtomicLong();

		LocationRebuildResult result = service.rebuild(LocationRebuildScope.ALL, lastProgress::set);

		Assertions.assertThat(result.candidates()).isEqualTo(totalMedia);
		Assertions.assertThat(result.resolved()).isEqualTo(totalMedia);
		Assertions.assertThat(lastProgress.get()).isEqualTo(totalMedia);
	}

	@Test
	void rebuildFallsBackToSingleThreadWhenParallelismIsNonPositive() {
		LocationRebuildProperties properties = new LocationRebuildProperties();
		properties.setParallelism(0);

		LocationRebuildService singleThreaded = new LocationRebuildService(repository, mediaLocationService,
				properties);

		when(repository.findAllResolvableIds(eq(0L), any())).thenReturn(List.of(1L));
		when(repository.findAllResolvableIds(eq(1L), any())).thenReturn(List.of());
		when(repository.findCoordinatesByIds(any())).thenReturn(List.of(projection(1L, -25.0, -49.0)));
		when(mediaLocationService.resolveAndPersist(eq(1L), any())).thenReturn(true);

		LocationRebuildResult result = singleThreaded.rebuild(LocationRebuildScope.ALL, null);

		Assertions.assertThat(result.resolved()).isEqualTo(1);
	}

	@Test
	void rebuildCountsErrorsAndContinues() {
		when(repository.findAllResolvableIds(eq(0L), any())).thenReturn(List.of(1L));
		when(repository.findAllResolvableIds(eq(1L), any())).thenReturn(List.of());
		when(repository.findCoordinatesByIds(any())).thenReturn(List.of(projection(1L, -25.0, -49.0)));
		when(mediaLocationService.resolveAndPersist(eq(1L), any())).thenThrow(new RuntimeException("boom"));

		LocationRebuildResult result = service.rebuild(LocationRebuildScope.ALL, null);

		Assertions.assertThat(result.candidates()).isEqualTo(1);
		Assertions.assertThat(result.errors()).isEqualTo(1);
		Assertions.assertThat(result.resolved()).isZero();
	}

	@Test
	void rebuildTreatsMissingCoordinatesAsUnresolved() {
		when(repository.findAllResolvableIds(eq(0L), any())).thenReturn(List.of(1L));
		when(repository.findAllResolvableIds(eq(1L), any())).thenReturn(List.of());
		when(repository.findCoordinatesByIds(any())).thenReturn(List.of(projection(1L, null, -49.0)));

		LocationRebuildResult result = service.rebuild(LocationRebuildScope.ALL, null);

		Assertions.assertThat(result.unresolved()).isEqualTo(1);
		Assertions.assertThat(result.resolved()).isZero();
	}

	@Test
	void rebuildUsesPendingQueryForPendingScope() {
		when(repository.findPendingIds(eq(0L), any())).thenReturn(List.of());

		LocationRebuildResult result = service.rebuild(LocationRebuildScope.PENDING, null);

		Assertions.assertThat(result.scope()).isEqualTo(LocationRebuildScope.PENDING);
		Assertions.assertThat(result.candidates()).isZero();
	}

	@Test
	void rebuildUsesConfidenceQueryForLowConfidenceScope() {
		when(repository.findIdsByConfidence(any(), eq(0L), any())).thenReturn(List.of());

		LocationRebuildResult result = service.rebuild(LocationRebuildScope.LOW_CONFIDENCE, null);

		Assertions.assertThat(result.scope()).isEqualTo(LocationRebuildScope.LOW_CONFIDENCE);
		Assertions.assertThat(result.candidates()).isZero();
	}

	private static MediaCoordinatesProjection projection(Long id, Double latitude, Double longitude) {
		return new MediaCoordinatesProjection() {

			@Override
			public Long getId() {
				return id;
			}

			@Override
			public Double getLatitude() {
				return latitude;
			}

			@Override
			public Double getLongitude() {
				return longitude;
			}
		};
	}
}