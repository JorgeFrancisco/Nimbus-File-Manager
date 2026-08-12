package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants.GeolocationConstants;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Coordinates;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationFallbackMode;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationSubdivision;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.MediaGeoLocation;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.ResolvedPlace;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;

class GeolocationCoreTest {

	private final LocationConfidencePolicy confidencePolicy = new LocationConfidencePolicy();
	private final LocationOrganizationPolicy organizationPolicy = new LocationOrganizationPolicy();

	@Test
	void shouldValidateCoordinatesAndCreateStableRoundedCacheKey() {
		Assertions.assertThat(Coordinates.of(-25.4284, -49.2733).roundedKey()).isEqualTo("-25.4284:-49.2733");
		Assertions.assertThat(Coordinates.of(null, null)).isNull();
		Assertions.assertThat(Coordinates.of(null, -49.0)).isNull();
		Assertions.assertThat(Coordinates.of(-25.0, null)).isNull();
		Assertions.assertThat(Coordinates.of(0.0, 0.0)).isNull();
		Assertions.assertThat(Coordinates.of(0.0, -49.0)).isEqualTo(new Coordinates(0, -49));
		Assertions.assertThat(Coordinates.of(-25.0, 0.0)).isEqualTo(new Coordinates(-25, 0));
		Assertions.assertThat(Coordinates.of(-90.0, -180.0)).isEqualTo(new Coordinates(-90, -180));
		Assertions.assertThat(Coordinates.of(90.0, 180.0)).isEqualTo(new Coordinates(90, 180));
		Assertions.assertThat(Coordinates.of(-90.0001, 0.0)).isNull();
		Assertions.assertThat(Coordinates.of(90.0001, 0.0)).isNull();
		Assertions.assertThat(Coordinates.of(1.0, -180.0001)).isNull();
		Assertions.assertThat(Coordinates.of(1.0, 180.0001)).isNull();
		Assertions.assertThat(Coordinates.of(Double.NaN, 1.0)).isNull();
		Assertions.assertThatThrownBy(() -> new Coordinates(0, 0)).isInstanceOf(IllegalArgumentException.class);
		Assertions.assertThatThrownBy(() -> new Coordinates(0, 181)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void shouldAssignConfidenceByAdministrativeLevel() {
		Assertions.assertThat(confidencePolicy.confidenceForKind(AdminBoundaryKind.MUNICIPALITY))
				.isEqualTo(LocationConfidence.VERY_HIGH);
		Assertions.assertThat(confidencePolicy.confidenceForKind(AdminBoundaryKind.STATE))
				.isEqualTo(LocationConfidence.MEDIUM);
		Assertions.assertThat(confidencePolicy.confidenceForKind(AdminBoundaryKind.COUNTRY))
				.isEqualTo(LocationConfidence.LOW);
		Assertions.assertThat(confidencePolicy.confidenceForKind(null)).isEqualTo(LocationConfidence.VERY_LOW);
	}

	@Test
	void shouldBuildSafeOrganizationSegmentsAndHonorConfidenceFallback() {
		MediaGeoLocation location = MediaGeoLocation.builder()
				.place(ResolvedPlace.builder().countryName("Brasil").stateName("Paraná").cityName("Curitiba/Centro")
						.confidence(LocationConfidence.HIGH).build())
				.build();

		Assertions
				.assertThat(organizationPolicy.subdivisionSegments(location, LocationSubdivision.COUNTRY_STATE_CITY,
						LocationConfidence.HIGH, LocationFallbackMode.IGNORE))
				.containsExactly("Brasil", "Paraná", "Curitiba Centro");
		Assertions
				.assertThat(organizationPolicy.subdivisionSegments(location, LocationSubdivision.COUNTRY_STATE_CITY,
						LocationConfidence.VERY_HIGH, LocationFallbackMode.FALLBACK_FOLDER))
				.isEqualTo(List.of(GeolocationConstants.FALLBACK_FOLDER_NAME));
	}

	@Test
	void theDisplayLabelSkipsThePartsThatAreMissing() {
		Assertions.assertThat(LocationDisplay.fullLabel("Curitiba", null, "Brasil")).isEqualTo("Curitiba, Brasil");
	}
}