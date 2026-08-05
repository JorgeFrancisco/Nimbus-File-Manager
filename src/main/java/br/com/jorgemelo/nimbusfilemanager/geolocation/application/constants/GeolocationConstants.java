package br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants;

/**
 * Contract data constants for the geolocation domain: the page/preference keys
 * that remember the last-picked location-rebuild scope (shared between the
 * geodata actions controller and the read-side settings model) and the message
 * keys the geodata actions surface when an inventory or a run in flight blocks
 * them.
 */
public final class GeolocationConstants {

	public static final String GEO_PAGE_KEY = "geodata";
	public static final String GEO_REBUILD_SCOPE_KEY = "rebuildScope";
	public static final String FALLBACK_FOLDER_NAME = "SEM_LOCALIZACAO_CONFIAVEL";
	public static final String MESSAGE_BLOCKED = "backend.settings.blocked";
	public static final String MESSAGE_WAIT_IMPORT = "backend.settings.waitGeoImport";

	private GeolocationConstants() {
	}
}