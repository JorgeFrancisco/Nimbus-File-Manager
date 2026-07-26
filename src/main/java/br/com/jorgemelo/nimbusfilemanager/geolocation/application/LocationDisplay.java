package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import java.util.ArrayList;
import java.util.List;

/**
 * Display helpers for resolved locations, shared by every screen (photo and
 * video details, timeline, previews). Keeps user-facing place formats in one
 * place: "Curitiba, Paraná, Brasil", "Curitiba, Paraná".
 */
public final class LocationDisplay {

	private LocationDisplay() {
	}

	/** "Curitiba, Paraná, Brasil" (skips blank parts), or null. */
	public static String fullLabel(String cityName, String stateName, String countryName) {
		List<String> parts = new ArrayList<>();

		add(parts, cityName);
		add(parts, stateName);
		add(parts, countryName);

		return parts.isEmpty() ? null : String.join(", ", parts);
	}

	/**
	 * Same as {@link #fullLabel(String, String, String)}, falling back to
	 * {@code openSeaLabel} when the coordinate resolved to open water - there is no
	 * place name to show, but there is something true to say. The caller passes the
	 * already-translated wording, because formatting is not where messages are
	 * resolved.
	 */
	public static String fullLabel(String cityName, String stateName, String countryName, boolean openSea,
			String openSeaLabel) {
		String label = fullLabel(cityName, stateName, countryName);

		return label != null || !openSea ? label : openSeaLabel;
	}

	/** Compact card label: "Curitiba, Paraná", falling back to country. */
	public static String shortLabel(String cityName, String stateName, String countryName) {
		List<String> parts = new ArrayList<>();

		add(parts, cityName);
		add(parts, stateName);

		if (parts.isEmpty()) {
			add(parts, countryName);
		}

		return parts.isEmpty() ? null : String.join(", ", parts);
	}

	/**
	 * Compact variant of
	 * {@link #fullLabel(String, String, String, boolean, String)}.
	 */
	public static String shortLabel(String cityName, String stateName, String countryName, boolean openSea,
			String openSeaLabel) {
		String label = shortLabel(cityName, stateName, countryName);

		return label != null || !openSea ? label : openSeaLabel;
	}

	private static void add(List<String> parts, String value) {
		if (value != null && !value.isBlank()) {
			parts.add(value.strip());
		}
	}
}