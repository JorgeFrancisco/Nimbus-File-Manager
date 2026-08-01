package br.com.jorgemelo.nimbusfilemanager.settings.application.constants;

/**
 * Contract data constants for the settings domain: the {@code AppSetting} keys
 * (and the default time zone value) read across the application to look up
 * configured values. Keeping them in one place lets any collaborator reference
 * a key without depending on {@link AppSettingService} itself.
 */
public final class SettingsConstants {

	public static final String DEFAULT_TIMEZONE = "America/Sao_Paulo";
	public static final String TIMEZONE = "nimbus-file-manager.timezone";
	public static final String TOOL_FFPROBE = "nimbus-file-manager.tools.ffprobe";
	public static final String TOOL_FFMPEG = "nimbus-file-manager.tools.ffmpeg";
	public static final String TOOL_DOWNLOAD_URL = "nimbus-file-manager.tools.download-url";
	public static final String TOOL_AUTO_INSTALL = "nimbus-file-manager.tools.auto-install";
	public static final String API_MAX_PAGE_SIZE = "nimbus-file-manager.api.max-page-size";
	public static final String API_DEFAULT_FOLDER_LIMIT = "nimbus-file-manager.api.default-folder-limit";
	public static final String API_MAX_FOLDER_LIMIT = "nimbus-file-manager.api.max-folder-limit";
	public static final String IDLE_TIMEOUT_MINUTES = "nimbus-file-manager.security.idle-timeout-minutes";
	public static final String MAX_FAILED_LOGIN_ATTEMPTS = "nimbus-file-manager.security.max-failed-login-attempts";
	public static final String LOCKOUT_DURATION_MINUTES = "nimbus-file-manager.security.lockout-duration-minutes";
	public static final String WATCH_FOLDER = "nimbus-file-manager.inventory.watch-folder";
	public static final String WATCH_RECURSIVE = "nimbus-file-manager.inventory.watch-recursive";
	public static final String WATCH_INCLUDE_HIDDEN = "nimbus-file-manager.inventory.watch-include-hidden";
	public static final String WATCH_CALCULATE_HASHES = "nimbus-file-manager.inventory.watch-calculate-hashes";
	public static final String WATCH_FORCE_ANALYSIS = "nimbus-file-manager.inventory.watch-force-analysis";
	public static final String TRASH_FOLDER = "nimbus-file-manager.duplicates.trash-folder";
	public static final String TRASH_RETENTION_DAYS = "nimbus-file-manager.duplicates.trash-retention-days";
	public static final String CATALOG_MISSING_RETENTION_DAYS = "nimbus-file-manager.catalog.missing-retention-days";
	public static final String BACKUP_FOLDER = "nimbus-file-manager.backup.folder";
	public static final String LOCATION_ENABLED = "nimbus-file-manager.location.enabled";
	public static final String LOCATION_PROVIDER = "nimbus-file-manager.location.provider";
	public static final String BOUNDARY_ADM0_URL = "nimbus-file-manager.location.boundary.adm0-url";
	public static final String BOUNDARY_ADM1_URL = "nimbus-file-manager.location.boundary.adm1-url";
	public static final String BOUNDARY_ADM2_URL = "nimbus-file-manager.location.boundary.adm2-url";
	public static final String BOUNDARY_GBOPEN_API_URL = "nimbus-file-manager.location.boundary.gbopen-api-url";
	public static final String BOUNDARY_AUTO_UPDATE_ENABLED = "nimbus-file-manager.location.boundary.auto-update-enabled";
	public static final String BOUNDARY_AUTO_UPDATE_TIME = "nimbus-file-manager.location.boundary.auto-update-time";
	public static final String BOUNDARY_AUTO_TERRITORIES = "nimbus-file-manager.location.boundary.auto-complete-territories";
	public static final String MAP_ENABLED = "nimbus-file-manager.map.enabled";
	public static final String MAP_TILE_URL = "nimbus-file-manager.map.tile-url";
	public static final String MAP_TILE_ATTRIBUTION = "nimbus-file-manager.map.tile-attribution";
	public static final String MAP_MAX_ZOOM = "nimbus-file-manager.map.max-zoom";
	public static final String SCAN_EXCLUDED_EXTENSIONS = "nimbus-file-manager.scan.excluded-extensions";
	public static final String SCAN_EXCLUDED_FOLDERS = "nimbus-file-manager.scan.excluded-folders";

	private SettingsConstants() {
	}
}