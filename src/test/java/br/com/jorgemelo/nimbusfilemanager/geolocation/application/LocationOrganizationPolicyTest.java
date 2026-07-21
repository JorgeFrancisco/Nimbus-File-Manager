package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationFallbackMode;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationSubdivision;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.MediaGeoLocation;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.ResolvedPlace;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;

/**
 * Folder-segment and label rules of the organization policy: confidence gate,
 * fallback modes, subdivision depth, folder-name sanitization and display
 * labels.
 */
class LocationOrganizationPolicyTest {

	private final LocationOrganizationPolicy policy = new LocationOrganizationPolicy();

	private static MediaGeoLocation location(String country, String state, String city, LocationConfidence confidence) {
		return MediaGeoLocation.builder().place(ResolvedPlace.builder().countryName(country).stateName(state)
				.cityName(city).confidence(confidence).build()).build();
	}

	@Test
	void returnsNoSegmentsWhenSubdivisionIsNoneOrNull() {
		MediaGeoLocation location = location("Brasil", "Paraná", "Curitiba", LocationConfidence.VERY_HIGH);

		Assertions.assertThat(policy.subdivisionSegments(location, LocationSubdivision.NONE, LocationConfidence.LOW,
				LocationFallbackMode.IGNORE)).isEmpty();
		Assertions
				.assertThat(
						policy.subdivisionSegments(location, null, LocationConfidence.LOW, LocationFallbackMode.IGNORE))
				.isEmpty();
	}

	@Test
	void buildsSegmentsAccordingToSubdivisionDepth() {
		MediaGeoLocation location = location("Brasil", "Paraná", "Curitiba", LocationConfidence.HIGH);

		Assertions.assertThat(policy.subdivisionSegments(location, LocationSubdivision.COUNTRY,
				LocationConfidence.MEDIUM, LocationFallbackMode.IGNORE)).containsExactly("Brasil");
		Assertions.assertThat(policy.subdivisionSegments(location, LocationSubdivision.COUNTRY_STATE,
				LocationConfidence.MEDIUM, LocationFallbackMode.IGNORE)).containsExactly("Brasil", "Paraná");
		Assertions
				.assertThat(policy.subdivisionSegments(location, LocationSubdivision.COUNTRY_STATE_CITY,
						LocationConfidence.MEDIUM, LocationFallbackMode.IGNORE))
				.containsExactly("Brasil", "Paraná", "Curitiba");
	}

	@Test
	void usesCountryCodeWhenCountryNameIsBlank() {
		MediaGeoLocation location = MediaGeoLocation.builder().place(
				ResolvedPlace.builder().countryName("  ").countryCode("BR").confidence(LocationConfidence.HIGH).build())
				.build();

		Assertions.assertThat(policy.subdivisionSegments(location, LocationSubdivision.COUNTRY,
				LocationConfidence.MEDIUM, LocationFallbackMode.IGNORE)).containsExactly("BR");
	}

	@Test
	void lowConfidenceGoesToFallbackFolderOrIsIgnored() {
		MediaGeoLocation location = location("Brasil", "Paraná", "Curitiba", LocationConfidence.LOW);

		Assertions
				.assertThat(policy.subdivisionSegments(location, LocationSubdivision.COUNTRY, LocationConfidence.HIGH,
						LocationFallbackMode.FALLBACK_FOLDER))
				.containsExactly(LocationOrganizationPolicy.FALLBACK_FOLDER_NAME);
		Assertions.assertThat(policy.subdivisionSegments(location, LocationSubdivision.COUNTRY, LocationConfidence.HIGH,
				LocationFallbackMode.IGNORE)).isEmpty();
	}

	@Test
	void nullFallbackModeIsTreatedAsIgnore() {
		MediaGeoLocation location = location("Brasil", null, null, LocationConfidence.LOW);

		Assertions.assertThat(
				policy.subdivisionSegments(location, LocationSubdivision.COUNTRY, LocationConfidence.HIGH, null))
				.isEmpty();
	}

	@Test
	void manualLocationQualifiesRegardlessOfConfidence() {
		MediaGeoLocation manual = MediaGeoLocation.builder().manual(true)
				.place(ResolvedPlace.builder().countryName("Brasil").confidence(LocationConfidence.VERY_LOW).build())
				.build();

		Assertions.assertThat(policy.subdivisionSegments(manual, LocationSubdivision.COUNTRY,
				LocationConfidence.VERY_HIGH, LocationFallbackMode.IGNORE)).containsExactly("Brasil");
	}

	@Test
	void qualifyingButEmptyNamesFallBackWhenConfigured() {
		MediaGeoLocation location = MediaGeoLocation.builder()
				.place(ResolvedPlace.builder().confidence(LocationConfidence.HIGH).build()).build();

		Assertions
				.assertThat(policy.subdivisionSegments(location, LocationSubdivision.COUNTRY, LocationConfidence.MEDIUM,
						LocationFallbackMode.FALLBACK_FOLDER))
				.containsExactly(LocationOrganizationPolicy.FALLBACK_FOLDER_NAME);
		Assertions.assertThat(policy.subdivisionSegments(location, LocationSubdivision.COUNTRY,
				LocationConfidence.MEDIUM, LocationFallbackMode.IGNORE)).isEmpty();
	}

	@Test
	void nullConfidenceDoesNotQualify() {
		MediaGeoLocation location = location("Brasil", null, null, null);

		Assertions.assertThat(policy.subdivisionSegments(location, LocationSubdivision.COUNTRY, LocationConfidence.LOW,
				LocationFallbackMode.IGNORE)).isEmpty();
	}

	@Test
	void sanitizesReservedCharactersAndTrailingDots() {
		MediaGeoLocation location = MediaGeoLocation.builder().place(ResolvedPlace.builder().countryName("A/B:C*?  ")
				.stateName("Estado.").confidence(LocationConfidence.HIGH).build()).build();

		List<String> segments = policy.subdivisionSegments(location, LocationSubdivision.COUNTRY_STATE,
				LocationConfidence.MEDIUM, LocationFallbackMode.IGNORE);

		Assertions.assertThat(segments).containsExactly("A B C", "Estado");
	}

	@Test
	void truncatesOverlongSegments() {
		String longName = "N".repeat(150);

		MediaGeoLocation location = MediaGeoLocation.builder()
				.place(ResolvedPlace.builder().countryName(longName).confidence(LocationConfidence.HIGH).build())
				.build();

		List<String> segments = policy.subdivisionSegments(location, LocationSubdivision.COUNTRY,
				LocationConfidence.MEDIUM, LocationFallbackMode.IGNORE);

		Assertions.assertThat(segments).hasSize(1);
		Assertions.assertThat(segments.get(0)).hasSize(100);
	}

	@Test
	void displayLabelJoinsCityStateAndCountry() {
		MediaGeoLocation location = location("Brasil", "Paraná", "Curitiba", LocationConfidence.HIGH);

		Assertions.assertThat(policy.displayLabel(location)).isEqualTo("Curitiba, Paraná, Brasil");
	}

	@Test
	void displayLabelFallsBackToCountryCodeAndHandlesNulls() {
		MediaGeoLocation onlyCode = MediaGeoLocation.builder().place(ResolvedPlace.builder().countryCode("BR").build())
				.build();

		Assertions.assertThat(policy.displayLabel(onlyCode)).isEqualTo("BR");
		Assertions.assertThat(policy.displayLabel(null)).isNull();
		Assertions.assertThat(policy.displayLabel(MediaGeoLocation.builder().build())).isNull();
	}

	@Test
	void confidenceAndDistanceLabels() {
		MediaGeoLocation location = MediaGeoLocation.builder()
				.place(ResolvedPlace.builder().confidence(LocationConfidence.VERY_HIGH).distanceKm(2.4).build())
				.build();

		Assertions.assertThat(policy.confidenceLabel(location)).isEqualTo("Muito alta");
		Assertions.assertThat(policy.distanceLabel(location)).isEqualTo("2,4 km");
		Assertions.assertThat(policy.confidenceLabel(null)).isNull();
		Assertions.assertThat(policy.distanceLabel(MediaGeoLocation.builder().build())).isNull();
	}

	@Test
	void nullOrPlacelessLocationNeverQualifies() {
		Assertions.assertThat(policy.subdivisionSegments(null, LocationSubdivision.COUNTRY, LocationConfidence.LOW,
				LocationFallbackMode.FALLBACK_FOLDER)).containsExactly(LocationOrganizationPolicy.FALLBACK_FOLDER_NAME);

		MediaGeoLocation placeless = MediaGeoLocation.builder().build();

		Assertions.assertThat(policy.subdivisionSegments(placeless, LocationSubdivision.COUNTRY, LocationConfidence.LOW,
				LocationFallbackMode.IGNORE)).isEmpty();
	}

	@Test
	void dropsNamesThatSanitizeToADotSegment() {
		MediaGeoLocation location = MediaGeoLocation.builder()
				.place(ResolvedPlace.builder().countryName("..").confidence(LocationConfidence.HIGH).build()).build();

		Assertions.assertThat(policy.subdivisionSegments(location, LocationSubdivision.COUNTRY, LocationConfidence.MEDIUM,
				LocationFallbackMode.FALLBACK_FOLDER)).containsExactly(LocationOrganizationPolicy.FALLBACK_FOLDER_NAME);
	}

	@Test
	void displayLabelSkipsBlankPartsAndIsNullWhenEverythingIsBlank() {
		MediaGeoLocation allBlank = MediaGeoLocation.builder().place(ResolvedPlace.builder().cityName("  ")
				.stateName("  ").countryName("  ").countryCode("  ").confidence(LocationConfidence.HIGH).build()).build();

		Assertions.assertThat(policy.displayLabel(allBlank)).isNull();

		MediaGeoLocation stateOnly = MediaGeoLocation.builder()
				.place(ResolvedPlace.builder().stateName("Paraná").build()).build();

		Assertions.assertThat(policy.displayLabel(stateOnly)).isEqualTo("Paraná");
	}

	@Test
	void confidenceAndDistanceLabelsAreNullWhenPlaceOrValueMissing() {
		Assertions.assertThat(policy.confidenceLabel(MediaGeoLocation.builder().build())).isNull();
		Assertions.assertThat(policy.distanceLabel(null)).isNull();

		MediaGeoLocation noConfidence = MediaGeoLocation.builder().place(ResolvedPlace.builder().build()).build();

		Assertions.assertThat(policy.confidenceLabel(noConfidence)).isNull();

		MediaGeoLocation noDistance = MediaGeoLocation.builder()
				.place(ResolvedPlace.builder().confidence(LocationConfidence.HIGH).build()).build();

		Assertions.assertThat(policy.distanceLabel(noDistance)).isNull();
	}
}