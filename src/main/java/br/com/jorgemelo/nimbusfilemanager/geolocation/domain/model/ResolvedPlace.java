package br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model;

import java.time.LocalDateTime;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationProvider;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A resolved place (country/state/city + provenance) shared, as an
 * {@link Embeddable}, by {@link MediaGeoLocation} (the resolved location of a
 * media) and {@link GeoResolutionCache} (the reverse-geocoding cache).
 * Extracted to keep the two in lock-step: any new field (e.g. neighborhood,
 * postal code) is added once here instead of drifting between two identical
 * column blocks.
 *
 * <p>
 * The column names match what both tables already have, so this is a pure code
 * refactor with no schema change.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
public class ResolvedPlace {

	@Column(name = "country_code", length = 2)
	private String countryCode;

	@Column(name = "country_name", length = 120)
	private String countryName;

	@Column(name = "state_name", length = 120)
	private String stateName;

	@Column(name = "city_name", length = 200)
	private String cityName;

	@Column(name = "distance_km")
	private Double distanceKm;

	/**
	 * The coordinate sits outside every administrative boundary and beyond the
	 * tolerance of the nearest one: open water. Kept as a fact of its own so it
	 * never has to be spelled into a place name, which would freeze one language
	 * into the database and pollute the country of every screen that groups by it.
	 */
	@Column(name = "open_sea", nullable = false)
	private boolean openSea;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private LocationConfidence confidence;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private LocationProvider provider;

	@Column(name = "dataset_version", length = 50)
	private String datasetVersion;

	@Column(name = "resolved_at", nullable = false)
	private LocalDateTime resolvedAt;
}