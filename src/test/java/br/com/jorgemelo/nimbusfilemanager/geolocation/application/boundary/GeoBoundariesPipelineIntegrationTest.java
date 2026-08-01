package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.io.WKBWriter;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.LocationConfidencePolicy;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Coordinates;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LocationResolution;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.GeoAdminBoundary;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoAdminBoundaryRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.LocationRebuildProperties;

/**
 * End-to-end validation of the geoBoundaries pipeline against real
 * geoBoundaries-format GeoJSON (small committed fixtures for Brazil / Paraná /
 * Curitiba+Pinhais): GeoJSON streaming read -> ISO3 to ISO2 + WKB -> bounding
 * box candidates -> Point-in-Polygon. The acceptance coordinate must resolve to
 * Curitiba, Paraná, Brasil; an offshore coordinate must approximate to the
 * nearest municipality with LOW confidence.
 */
class GeoBoundariesPipelineIntegrationTest {

	private final GeoJsonBoundaryReader reader = new GeoJsonBoundaryReader(new ObjectMapper());
	private final GeoAdminBoundaryRepository repository = mock(GeoAdminBoundaryRepository.class);

	private BoundaryGeometryCache cache() {
		return new BoundaryGeometryCache(repository, new LocationRebuildProperties());
	}

	@Test
	void acceptanceCoordinateShouldResolveToCuritibaParanaBrasil() {
		List<GeoAdminBoundary> states = load("geo/cgaz-adm1-sample.geojson", AdminBoundaryKind.STATE);
		List<GeoAdminBoundary> municipalities = load("geo/cgaz-adm2-sample.geojson", AdminBoundaryKind.MUNICIPALITY);

		when(repository.findCandidates(eq(AdminBoundaryKind.MUNICIPALITY), anyDouble(), anyDouble()))
				.thenReturn(municipalities);
		when(repository.findCandidatesInCountry(eq(AdminBoundaryKind.STATE), eq("BR"), anyDouble(), anyDouble()))
				.thenReturn(states);

		BoundaryGeometryCache cache = cache();

		AdminBoundaryPipStrategy strategy = new AdminBoundaryPipStrategy(new AdminBoundaryResolver(repository, cache),
				cache, new LocationConfidencePolicy(), Clock.systemDefaultZone());

		Optional<LocationResolution> resolution = strategy
				.resolve(new Coordinates(-25.388261795043945, -49.228511810302734));

		Assertions.assertThat(resolution).isPresent();
		Assertions.assertThat(resolution.get().cityName()).isEqualTo("Curitiba");
		Assertions.assertThat(resolution.get().stateName()).isEqualTo("Paraná");
		Assertions.assertThat(resolution.get().countryName()).isEqualTo("Brasil");
		Assertions.assertThat(resolution.get().countryCode()).isEqualTo("BR");
		Assertions.assertThat(resolution.get().confidence()).isEqualTo(LocationConfidence.VERY_HIGH);
	}

	@Test
	void offshoreCoordinateShouldApproximateToNearestMunicipality() {
		List<GeoAdminBoundary> states = load("geo/cgaz-adm1-sample.geojson", AdminBoundaryKind.STATE);
		List<GeoAdminBoundary> municipalities = load("geo/cgaz-adm2-sample.geojson", AdminBoundaryKind.MUNICIPALITY);

		when(repository.findCandidates(eq(AdminBoundaryKind.MUNICIPALITY), anyDouble(), anyDouble()))
				.thenReturn(municipalities);
		when(repository.findCandidatesNear(eq(AdminBoundaryKind.MUNICIPALITY), anyDouble(), anyDouble(), anyDouble(),
				anyDouble())).thenReturn(municipalities);
		when(repository.findCandidatesInCountry(eq(AdminBoundaryKind.STATE), eq("BR"), anyDouble(), anyDouble()))
				.thenReturn(states);

		BoundaryGeometryCache cache = cache();

		AdminBoundaryPipStrategy strategy = new AdminBoundaryPipStrategy(new AdminBoundaryResolver(repository, cache),
				cache, new LocationConfidencePolicy(), Clock.systemDefaultZone());

		// ~5 km east of Pinhais' eastern edge: no polygon contains it.
		Optional<LocationResolution> resolution = strategy.resolve(new Coordinates(-25.475, -49.055));

		Assertions.assertThat(resolution).isPresent();
		Assertions.assertThat(resolution.get().cityName()).isEqualTo("Pinhais");
		Assertions.assertThat(resolution.get().stateName()).isEqualTo("Paraná");
		Assertions.assertThat(resolution.get().countryCode()).isEqualTo("BR");
		Assertions.assertThat(resolution.get().confidence()).isEqualTo(LocationConfidence.LOW);
		Assertions.assertThat(resolution.get().distanceKm()).isBetween(3.0, 7.0);
	}

	/**
	 * Open water resolves to the fact itself rather than to nothing: no place
	 * names, the lowest confidence and the flag the screens label from. It used to
	 * stay unresolved, which left the coordinate being retried by every rebuild
	 * forever.
	 */
	@Test
	void openSeaCoordinateShouldResolveAsOpenSea() {
		BoundaryGeometryCache cache = cache();

		AdminBoundaryPipStrategy strategy = new AdminBoundaryPipStrategy(new AdminBoundaryResolver(repository, cache),
				cache, new LocationConfidencePolicy(), Clock.systemDefaultZone());

		Optional<LocationResolution> resolution = strategy.resolve(new Coordinates(-30.0, -20.0));

		Assertions.assertThat(resolution).isPresent();
		Assertions.assertThat(resolution.get().openSea()).isTrue();
		Assertions.assertThat(resolution.get().countryName()).isNull();
		Assertions.assertThat(resolution.get().confidence()).isEqualTo(LocationConfidence.VERY_LOW);
	}

	/** Reads a GeoJSON fixture through the real reader and builds boundary rows. */
	private List<GeoAdminBoundary> load(String resource, AdminBoundaryKind kind) {
		List<GeoAdminBoundary> boundaries = new ArrayList<>();
		WKBWriter writer = new WKBWriter();

		reader.read(toTempFile(resource), feature -> {
			String countryCode = CountryCodes.alpha2(feature.alpha3());

			Envelope envelope = feature.geometry().getEnvelopeInternal();

			boundaries.add(GeoAdminBoundary.builder().kind(kind).name(feature.name()).countryCode(countryCode)
					.countryName(CountryCodes.displayName(countryCode)).minLat(envelope.getMinY())
					.minLon(envelope.getMinX()).maxLat(envelope.getMaxY()).maxLon(envelope.getMaxX())
					.geometry(writer.write(feature.geometry())).source("geoBoundaries").datasetVersion("test").build());
		});

		return boundaries;
	}

	private Path toTempFile(String resource) {
		try (InputStream in = new ClassPathResource(resource).getInputStream()) {
			Path temp = Files.createTempFile("cgaz", ".geojson");

			Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);

			temp.toFile().deleteOnExit();

			return temp;
		} catch (Exception e) {
			throw new IllegalStateException("Could not stage fixture " + resource, e);
		}
	}
}