package br.com.jorgemelo.nimbusfilemanager.settings.application;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.Option;

/**
 * Curated list of time zones offered by the settings screen combo, each with a
 * human-friendly pt-BR label mapped to its IANA id (the value actually stored
 * in {@link SettingsConstants#TIMEZONE}). Any valid IANA id is accepted on
 * save; this list only drives the dropdown, so a stored value outside it still
 * round-trips.
 */
public final class AppTimeZones {

	public static final List<Option> OPTIONS = List.of(new Option("America/Sao_Paulo", "São Paulo (Brasília)"),
			new Option("America/Manaus", "Manaus"), new Option("America/Rio_Branco", "Rio Branco"),
			new Option("America/Noronha", "Fernando de Noronha"), new Option("UTC", "UTC"),
			new Option("America/New_York", "Nova York"), new Option("America/Los_Angeles", "Los Angeles"),
			new Option("America/Mexico_City", "Cidade do México"),
			new Option("America/Argentina/Buenos_Aires", "Buenos Aires"), new Option("Europe/Lisbon", "Lisboa"),
			new Option("Europe/London", "Londres"), new Option("Europe/Paris", "Paris"),
			new Option("Europe/Madrid", "Madri"), new Option("Europe/Zurich", "Zurique"),
			new Option("Europe/Berlin", "Berlim"), new Option("Asia/Tokyo", "Tóquio"),
			new Option("Asia/Shanghai", "Xangai"), new Option("Australia/Sydney", "Sydney"));

	private AppTimeZones() {
	}
}