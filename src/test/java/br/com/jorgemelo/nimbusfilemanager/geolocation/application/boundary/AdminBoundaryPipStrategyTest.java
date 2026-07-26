package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.LocationConfidencePolicy;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Coordinates;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LocationResolution;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationProvider;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.GeoAdminBoundary;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoAdminBoundaryRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.LocationRebuildProperties;

/**
 * Point-in-Polygon resolver tests. The key case is the regression that
 * motivated the redesign: a point inside a wide Curitiba polygon whose centroid
 * is farther than neighbouring Pinhais' centroid. The legacy "nearest city"
 * model classified it as Pinhais; administrative containment classifies it
 * correctly as Curitiba.
 */
class AdminBoundaryPipStrategyTest {

	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
	private static final WKTReader WKT_READER = new WKTReader(GEOMETRY_FACTORY);

	// Curitiba is a wide polygon (centroid far to the west); Pinhais is a small
	// polygon to the east. The test point sits inside Curitiba but closer to
	// Pinhais' centroid, so nearest-centroid would be wrong.
	private static final String CURITIBA =
			"POLYGON((-49.55 -25.60, -49.18 -25.60, -49.18 -25.35, -49.55 -25.35, -49.55 -25.60))";
	private static final String PINHAIS =
			"POLYGON((-49.18 -25.60, -49.10 -25.60, -49.10 -25.35, -49.18 -25.35, -49.18 -25.60))";
	private static final String PARANA = "POLYGON((-54 -27, -48 -27, -48 -22, -54 -22, -54 -27))";
	private static final String BRAZIL = "POLYGON((-74 -34, -34 -34, -34 5, -74 5, -74 -34))";

	private final GeoAdminBoundaryRepository repository = mock(GeoAdminBoundaryRepository.class);

	private AdminBoundaryPipStrategy strategy() {
		when(repository.findCandidates(eq(AdminBoundaryKind.COUNTRY), anyDouble(), anyDouble()))
				.thenReturn(List.of(boundary(AdminBoundaryKind.COUNTRY, "Brazil", BRAZIL)));
		when(repository.findCandidates(eq(AdminBoundaryKind.STATE), anyDouble(), anyDouble()))
				.thenReturn(List.of(boundary(AdminBoundaryKind.STATE, "Paraná", PARANA)));
		when(repository.findCandidates(eq(AdminBoundaryKind.MUNICIPALITY), anyDouble(), anyDouble()))
				.thenReturn(List.of(boundary(AdminBoundaryKind.MUNICIPALITY, "Curitiba", CURITIBA),
						boundary(AdminBoundaryKind.MUNICIPALITY, "Pinhais", PINHAIS)));
		when(repository.findCandidatesInCountry(eq(AdminBoundaryKind.MUNICIPALITY), eq("BR"), anyDouble(), anyDouble()))
				.thenReturn(List.of(boundary(AdminBoundaryKind.MUNICIPALITY, "Curitiba", CURITIBA),
						boundary(AdminBoundaryKind.MUNICIPALITY, "Pinhais", PINHAIS)));
		when(repository.findCandidatesInCountry(eq(AdminBoundaryKind.STATE), eq("BR"), anyDouble(), anyDouble()))
				.thenReturn(List.of(boundary(AdminBoundaryKind.STATE, "Paraná", PARANA)));
		when(repository.count()).thenReturn(4L);

		BoundaryGeometryCache cache = cache();

		return new AdminBoundaryPipStrategy(new AdminBoundaryResolver(repository, cache), cache,
				new LocationConfidencePolicy(), Clock.systemDefaultZone());
	}

	private BoundaryGeometryCache cache() {
		return new BoundaryGeometryCache(repository, new LocationRebuildProperties());
	}

	@Test
	void shouldResolveRealCuritibaCoordinateByContainment() {
		// Exact coordinate from the acceptance criterion.
		Coordinates coordinate = new Coordinates(-25.388261795043945, -49.228511810302734);

		Optional<LocationResolution> resolution = strategy().resolve(coordinate);

		Assertions.assertThat(resolution).isPresent();
		Assertions.assertThat(resolution.get().cityName()).isEqualTo("Curitiba");
		Assertions.assertThat(resolution.get().stateName()).isEqualTo("Paraná");
		Assertions.assertThat(resolution.get().countryName()).isEqualTo("Brasil");
		Assertions.assertThat(resolution.get().countryCode()).isEqualTo("BR");
		Assertions.assertThat(resolution.get().confidence()).isEqualTo(LocationConfidence.VERY_HIGH);
		Assertions.assertThat(resolution.get().provider()).isEqualTo(LocationProvider.ADMIN_BOUNDARIES);
		Assertions.assertThat(resolution.get().distanceKm()).isNull();
	}

	@Test
	void shouldFallBackToStateWhenOutsideEveryMunicipality() {
		Optional<LocationResolution> resolution = strategy().resolve(new Coordinates(-24.0, -50.0));

		Assertions.assertThat(resolution).isPresent();
		Assertions.assertThat(resolution.get().cityName()).isNull();
		Assertions.assertThat(resolution.get().stateName()).isEqualTo("Paraná");
		Assertions.assertThat(resolution.get().countryName()).isEqualTo("Brasil");
		Assertions.assertThat(resolution.get().confidence()).isEqualTo(LocationConfidence.MEDIUM);
	}

	@Test
	void shouldApproximateToNearestMunicipalityWhenOutsideEveryPolygon() {
		// ~5 km east of Pinhais' eastern edge (lon -49.10): outside every
		// polygon, as a photo taken at sea would be. Only municipalities are
		// stubbed - at sea, no state or country polygon contains the point.
		when(repository.findCandidates(eq(AdminBoundaryKind.MUNICIPALITY), anyDouble(), anyDouble()))
				.thenReturn(List.of(boundary(AdminBoundaryKind.MUNICIPALITY, "Curitiba", CURITIBA),
						boundary(AdminBoundaryKind.MUNICIPALITY, "Pinhais", PINHAIS)));
		when(repository.findCandidatesNear(eq(AdminBoundaryKind.MUNICIPALITY), anyDouble(), anyDouble(), anyDouble(),
				anyDouble()))
				.thenReturn(List.of(boundary(AdminBoundaryKind.MUNICIPALITY, "Curitiba", CURITIBA),
						boundary(AdminBoundaryKind.MUNICIPALITY, "Pinhais", PINHAIS)));
		when(repository.findCandidatesInCountry(eq(AdminBoundaryKind.STATE), eq("BR"), anyDouble(), anyDouble()))
				.thenReturn(List.of(boundary(AdminBoundaryKind.STATE, "Paraná", PARANA)));

		BoundaryGeometryCache cache = cache();

		AdminBoundaryPipStrategy strategy = new AdminBoundaryPipStrategy(new AdminBoundaryResolver(repository, cache),
				cache, new LocationConfidencePolicy(), Clock.systemDefaultZone());

		Optional<LocationResolution> resolution = strategy.resolve(new Coordinates(-25.475, -49.055));

		Assertions.assertThat(resolution).isPresent();
		Assertions.assertThat(resolution.get().cityName()).isEqualTo("Pinhais");
		Assertions.assertThat(resolution.get().stateName()).isEqualTo("Paraná");
		Assertions.assertThat(resolution.get().countryCode()).isEqualTo("BR");
		Assertions.assertThat(resolution.get().confidence()).isEqualTo(LocationConfidence.LOW);
		Assertions.assertThat(resolution.get().distanceKm()).isBetween(3.0, 7.0);
	}

	@Test
	void shouldPreferTheSmallestPolygonWhenCountriesOverlap() {
		// A sovereign state's ADM0 drawn over a territory that also has its own
		// row (e.g. the Kingdom of the Netherlands over Aruba): the most
		// specific polygon must win, never the first one returned.
		GeoAdminBoundary kingdom = box(AdminBoundaryKind.COUNTRY, "Netherlands", "NL", "Países Baixos", -70.5, 11.5,
				7.5, 54.0);
		GeoAdminBoundary territory = box(AdminBoundaryKind.COUNTRY, "Aruba", "AW", "Aruba", -70.1, 12.3, -69.8, 12.7);

		when(repository.findCandidates(eq(AdminBoundaryKind.COUNTRY), anyDouble(), anyDouble()))
				.thenReturn(List.of(kingdom, territory));

		BoundaryGeometryCache cache = cache();

		AdminBoundaryPipStrategy strategy = new AdminBoundaryPipStrategy(new AdminBoundaryResolver(repository, cache),
				cache, new LocationConfidencePolicy(), Clock.systemDefaultZone());

		Optional<LocationResolution> resolution = strategy.resolve(new Coordinates(12.4327, -69.8767));

		Assertions.assertThat(resolution).isPresent();
		Assertions.assertThat(resolution.get().countryCode()).isEqualTo("AW");
		Assertions.assertThat(resolution.get().countryName()).isEqualTo("Aruba");
		Assertions.assertThat(resolution.get().confidence()).isEqualTo(LocationConfidence.LOW);
	}

	/**
	 * A photo taken at the waterline of a country the dataset has no subdivisions
	 * for: the point is metres outside the country's own polygon and there is no
	 * municipality anywhere to approximate to. Before this it stayed unresolved, so
	 * hundreds of files from a single trip had no location at all.
	 */
	@Test
	void shouldApproximateToTheCountryWhenItHasNoMunicipalityToApproximateTo() {
		GeoAdminBoundary aruba = box(AdminBoundaryKind.COUNTRY, "Aruba", "AW", "Aruba", -70.06, 12.41, -69.87, 12.62);

		when(repository.findCandidatesNear(eq(AdminBoundaryKind.COUNTRY), anyDouble(), anyDouble(), anyDouble(),
				anyDouble())).thenReturn(List.of(aruba));

		BoundaryGeometryCache cache = cache();

		AdminBoundaryPipStrategy strategy = new AdminBoundaryPipStrategy(new AdminBoundaryResolver(repository, cache),
				cache, new LocationConfidencePolicy(), Clock.systemDefaultZone());

		// ~2 km south of the island's southern edge (lat 12.41): inside its bounding
		// box, outside the polygon - which is what the pending files looked like.
		Optional<LocationResolution> resolution = strategy.resolve(new Coordinates(12.39, -69.95));

		Assertions.assertThat(resolution).isPresent();
		Assertions.assertThat(resolution.get().countryCode()).isEqualTo("AW");
		Assertions.assertThat(resolution.get().countryName()).isEqualTo("Aruba");
		Assertions.assertThat(resolution.get().cityName()).isNull();
		Assertions.assertThat(resolution.get().stateName()).isNull();
		Assertions.assertThat(resolution.get().confidence()).isEqualTo(LocationConfidence.LOW);
		Assertions.assertThat(resolution.get().distanceKm()).isBetween(0.5, 5.0);
	}

	/**
	 * The country approximation is the last resort, never a shortcut: where a
	 * municipality is within tolerance it still wins, keeping the city name that
	 * makes the location useful.
	 */
	@Test
	void shouldStillPreferANearbyMunicipalityOverTheCountryApproximation() {
		when(repository.findCandidatesNear(eq(AdminBoundaryKind.MUNICIPALITY), anyDouble(), anyDouble(), anyDouble(),
				anyDouble())).thenReturn(List.of(boundary(AdminBoundaryKind.MUNICIPALITY, "Pinhais", PINHAIS)));
		when(repository.findCandidatesNear(eq(AdminBoundaryKind.COUNTRY), anyDouble(), anyDouble(), anyDouble(),
				anyDouble())).thenReturn(List.of(boundary(AdminBoundaryKind.COUNTRY, "Brazil", BRAZIL)));

		BoundaryGeometryCache cache = cache();

		AdminBoundaryPipStrategy strategy = new AdminBoundaryPipStrategy(new AdminBoundaryResolver(repository, cache),
				cache, new LocationConfidencePolicy(), Clock.systemDefaultZone());

		Optional<LocationResolution> resolution = strategy.resolve(new Coordinates(-25.475, -49.055));

		Assertions.assertThat(resolution).isPresent();
		Assertions.assertThat(resolution.get().cityName()).isEqualTo("Pinhais");
	}

	@Test
	void shouldReturnEmptyWhenOutsideEveryBoundaryAndBeyondFallbackTolerance() {
		Assertions.assertThat(strategy().resolve(new Coordinates(-10.0, -100.0))).isEmpty();
	}

	@Test
	void shouldReportUnavailableWhenNoBoundariesImported() {
		when(repository.count()).thenReturn(0L);

		BoundaryGeometryCache cache = cache();

		AdminBoundaryPipStrategy strategy = new AdminBoundaryPipStrategy(new AdminBoundaryResolver(repository, cache),
				cache, new LocationConfidencePolicy(), Clock.systemDefaultZone());

		Assertions.assertThat(strategy.isAvailable()).isFalse();
	}

	private GeoAdminBoundary boundary(AdminBoundaryKind kind, String name, String wkt) {
		Geometry geometry = parse(wkt);

		Envelope envelope = geometry.getEnvelopeInternal();

		return GeoAdminBoundary.builder().kind(kind).name(name).countryCode("BR").countryName("Brasil")
				.minLat(envelope.getMinY()).minLon(envelope.getMinX()).maxLat(envelope.getMaxY())
				.maxLon(envelope.getMaxX()).geometry(new WKBWriter().write(geometry)).source("TEST")
				.datasetVersion("test").build();
	}

	private GeoAdminBoundary box(AdminBoundaryKind kind, String name, String countryCode, String countryName,
			double minLon, double minLat, double maxLon, double maxLat) {
		Geometry geometry = GEOMETRY_FACTORY.toGeometry(new Envelope(minLon, maxLon, minLat, maxLat));

		return GeoAdminBoundary.builder().kind(kind).name(name).countryCode(countryCode).countryName(countryName)
				.minLat(minLat).minLon(minLon).maxLat(maxLat).maxLon(maxLon).geometry(new WKBWriter().write(geometry))
				.source("TEST").datasetVersion("test").build();
	}

	private Geometry parse(String wkt) {
		try {
			return WKT_READER.read(wkt);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}