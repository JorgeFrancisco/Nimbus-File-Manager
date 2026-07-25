package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.GeoAdminBoundary;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoAdminBoundaryRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.LocationRebuildProperties;

/**
 * The in-memory acceleration layer over the boundary table: geometries are
 * parsed once per id and reused, availability is memoized until invalidated,
 * and the cache stays bounded and safe for transient (id-less) boundaries.
 */
class BoundaryGeometryCacheTest {

	private static final WKTReader WKT_READER = new WKTReader(new GeometryFactory());
	private static final String SQUARE = "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))";

	private final GeoAdminBoundaryRepository repository = mock(GeoAdminBoundaryRepository.class);

	@Test
	void parsesEachBoundaryOnceAndReusesThePreparedGeometry() {
		BoundaryGeometryCache cache = cache(new LocationRebuildProperties());
		GeoAdminBoundary boundary = boundary(1L, SQUARE);

		PreparedGeometry first = cache.geometry(boundary);
		PreparedGeometry second = cache.geometry(boundary);

		Assertions.assertThat(first).isNotNull();
		Assertions.assertThat(second).isSameAs(first);
	}

	@Test
	void doesNotCacheTransientBoundariesWithoutAnId() {
		BoundaryGeometryCache cache = cache(new LocationRebuildProperties());
		GeoAdminBoundary boundary = boundary(null, SQUARE);

		PreparedGeometry first = cache.geometry(boundary);
		PreparedGeometry second = cache.geometry(boundary);

		Assertions.assertThat(first).isNotNull();
		Assertions.assertThat(second).isNotSameAs(first);
	}

	@Test
	void returnsNullWhenTheStoredGeometryCannotBeParsed() {
		BoundaryGeometryCache cache = cache(new LocationRebuildProperties());
		GeoAdminBoundary boundary = GeoAdminBoundary.builder().id(1L).kind(AdminBoundaryKind.COUNTRY)
				.geometry(new byte[] { 1, 2, 3 }).build();

		Assertions.assertThat(cache.geometry(boundary)).isNull();
	}

	@Test
	void stopsCachingBeyondTheConfiguredMaxSize() {
		LocationRebuildProperties properties = new LocationRebuildProperties();
		properties.setGeometryCacheMaxSize(0);

		BoundaryGeometryCache cache = cache(properties);
		GeoAdminBoundary boundary = boundary(1L, SQUARE);

		Assertions.assertThat(cache.geometry(boundary)).isNotSameAs(cache.geometry(boundary));
	}

	@Test
	void memoizesAvailabilityUntilInvalidated() {
		when(repository.count()).thenReturn(3L);

		BoundaryGeometryCache cache = cache(new LocationRebuildProperties());

		Assertions.assertThat(cache.available()).isTrue();
		Assertions.assertThat(cache.available()).isTrue();

		verify(repository, times(1)).count();

		cache.invalidate();

		Assertions.assertThat(cache.available()).isTrue();

		verify(repository, times(2)).count();
	}

	@Test
	void invalidateDropsCachedGeometries() {
		BoundaryGeometryCache cache = cache(new LocationRebuildProperties());
		GeoAdminBoundary boundary = boundary(1L, SQUARE);

		PreparedGeometry before = cache.geometry(boundary);

		cache.invalidate();

		Assertions.assertThat(cache.geometry(boundary)).isNotSameAs(before);
	}

	private BoundaryGeometryCache cache(LocationRebuildProperties properties) {
		return new BoundaryGeometryCache(repository, properties);
	}

	private GeoAdminBoundary boundary(Long id, String wkt) {
		return GeoAdminBoundary.builder().id(id).kind(AdminBoundaryKind.MUNICIPALITY).name("Test").countryCode("BR")
				.countryName("Brasil").geometry(new WKBWriter().write(parse(wkt))).source("TEST").datasetVersion("test")
				.build();
	}

	private Geometry parse(String wkt) {
		try {
			return WKT_READER.read(wkt);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}