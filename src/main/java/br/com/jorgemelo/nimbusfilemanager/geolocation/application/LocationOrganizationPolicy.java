package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants.GeolocationConstants;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationFallbackMode;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationSubdivision;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.MediaGeoLocation;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.ResolvedPlace;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * Decides how a resolved location is used during organization: which folder
 * segments the subdivision produces, honoring the minimum confidence and the
 * fallback mode. Folder names are sanitized here, in one place. Knows nothing
 * about screens or providers.
 */
@Component
public class LocationOrganizationPolicy extends LocalizedComponent {

	private static final int MAX_SEGMENT_LENGTH = 100;

	private static final Pattern ILLEGAL_PATH_CHARS = Pattern.compile("[\\p{Cntrl}<>:\"/\\\\|?*]");

	private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

	private static final Pattern TRAILING_DOTS_SPACES = Pattern.compile("[. ]+$");

	/**
	 * @return folder segments to insert under the layout (possibly the fallback
	 * folder), or an empty list when location must not subdivide this media. Manual
	 * locations always qualify.
	 */
	public List<String> subdivisionSegments(MediaGeoLocation location, LocationSubdivision subdivision,
			LocationConfidence minimumConfidence, LocationFallbackMode fallback) {
		if (subdivision == null || subdivision == LocationSubdivision.NONE) {
			return List.of();
		}

		LocationFallbackMode fallbackMode = fallback == null ? LocationFallbackMode.IGNORE : fallback;

		if (!qualifies(location, minimumConfidence)) {
			return fallbackMode == LocationFallbackMode.FALLBACK_FOLDER
					? List.of(GeolocationConstants.FALLBACK_FOLDER_NAME)
					: List.of();
		}

		ResolvedPlace place = location.getPlace();

		List<String> segments = new ArrayList<>();

		addSegment(segments,
				place.getCountryName() != null && !place.getCountryName().isBlank() ? place.getCountryName()
						: place.getCountryCode());

		if (subdivision == LocationSubdivision.COUNTRY_STATE || subdivision == LocationSubdivision.COUNTRY_STATE_CITY) {
			addSegment(segments, place.getStateName());
		}

		if (subdivision == LocationSubdivision.COUNTRY_STATE_CITY) {
			addSegment(segments, place.getCityName());
		}

		if (segments.isEmpty()) {
			return fallbackMode == LocationFallbackMode.FALLBACK_FOLDER
					? List.of(GeolocationConstants.FALLBACK_FOLDER_NAME)
					: List.of();
		}

		return segments;
	}

	private boolean qualifies(MediaGeoLocation location, LocationConfidence minimumConfidence) {
		if (location == null || location.getPlace() == null || location.getPlace().getConfidence() == null) {
			return false;
		}

		if (location.isManual()) {
			return true;
		}

		return location.getPlace().getConfidence().atLeast(minimumConfidence);
	}

	private void addSegment(List<String> segments, String value) {
		String sanitized = sanitizeFolderName(value);

		if (!sanitized.isBlank()) {
			segments.add(sanitized);
		}
	}

	/**
	 * Keeps locality names safe as folder names on Windows and Linux: strips
	 * control characters and reserved separators, collapses whitespace and trims
	 * trailing dots/spaces (invalid on Windows).
	 */
	private String sanitizeFolderName(String value) {
		if (value == null) {
			return "";
		}

		String cleaned = WHITESPACE_RUN.matcher(ILLEGAL_PATH_CHARS.matcher(value).replaceAll(" ")).replaceAll(" ")
				.strip();

		cleaned = TRAILING_DOTS_SPACES.matcher(cleaned).replaceAll("");

		if (cleaned.length() > MAX_SEGMENT_LENGTH) {
			cleaned = cleaned.substring(0, MAX_SEGMENT_LENGTH).strip();
		}

		if (cleaned.equals(".") || cleaned.equals("..")) {
			return "";
		}

		return cleaned;
	}

	/** Display label used by previews: "Curitiba, Paraná, Brasil". */
	public String displayLabel(MediaGeoLocation location) {
		if (location == null || location.getPlace() == null) {
			return null;
		}

		ResolvedPlace place = location.getPlace();

		List<String> parts = new ArrayList<>();

		if (place.getCityName() != null && !place.getCityName().isBlank()) {
			parts.add(place.getCityName().strip());
		}

		if (place.getStateName() != null && !place.getStateName().isBlank()) {
			parts.add(place.getStateName().strip());
		}

		String country = place.getCountryName() != null && !place.getCountryName().isBlank() ? place.getCountryName()
				: place.getCountryCode();

		if (country != null && !country.isBlank()) {
			parts.add(country.strip());
		}

		return parts.isEmpty() ? null : String.join(", ", parts);
	}

	/** Display label for confidence, or null. */
	public String confidenceLabel(MediaGeoLocation location) {
		if (location == null || location.getPlace() == null || location.getPlace().getConfidence() == null) {
			return null;
		}

		return message("enum.locationConfidence." + location.getPlace().getConfidence().name());
	}

	/** pt-BR distance label: "2,4 km". */
	String distanceLabel(MediaGeoLocation location) {
		if (location == null || location.getPlace() == null || location.getPlace().getDistanceKm() == null) {
			return null;
		}

		return String.format(Locale.of("pt", "BR"), "%.1f km", location.getPlace().getDistanceKm());
	}
}