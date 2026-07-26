package br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto;

import java.time.LocalDateTime;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationProvider;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;

/**
 * Result of a reverse-geocoding resolution, provider-agnostic. This is what
 * gets persisted as {@code media_geo_location} and cached.
 */
public record LocationResolution(String countryCode, String countryName, String stateName, String cityName,
		Double distanceKm, LocationConfidence confidence, LocationProvider provider, String datasetVersion,
		LocalDateTime resolvedAt, boolean openSea) {

	/**
	 * A coordinate with no administrative boundary anywhere near it: open water,
	 * which is a fact worth recording rather than an absence to retry forever. The
	 * place names stay null - the screens label it from i18n, so the wording never
	 * gets frozen into the database in one language.
	 */
	public static LocationResolution openSea(LocationProvider provider, LocalDateTime resolvedAt) {
		return new LocationResolution(null, null, null, null, null, LocationConfidence.VERY_LOW, provider, null,
				resolvedAt, true);
	}
}