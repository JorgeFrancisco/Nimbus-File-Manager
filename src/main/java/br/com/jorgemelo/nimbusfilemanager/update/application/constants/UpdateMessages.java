package br.com.jorgemelo.nimbusfilemanager.update.application.constants;

/**
 * Message keys for the update flow. Held here rather than inline so the runner
 * that reports a refusal and the screen that asked for the install name the
 * same texts.
 */
public final class UpdateMessages {

	public static final String UP_TO_DATE = "backend.settings.updateUpToDate";
	public static final String FOUND = "backend.settings.updateFound";
	public static final String INSTALL_STARTED = "backend.settings.updateInstallStarted";
	public static final String STARTED = "backend.settings.updateStarted";
	public static final String ALREADY_RUNNING = "backend.settings.updateAlreadyRunning";
	public static final String NOTHING_TO_INSTALL = "backend.settings.updateNothingToInstall";
	public static final String UNSUPPORTED_PLATFORM = "backend.settings.updateUnsupportedPlatform";
	public static final String DOWNLOAD_FAILED = "backend.settings.updateDownloadFailed";
	public static final String CHECKSUM_UNAVAILABLE = "backend.settings.updateChecksumUnavailable";
	public static final String CHECKSUM_MISMATCH = "backend.settings.updateChecksumMismatch";
	public static final String COULD_NOT_START = "backend.settings.updateCouldNotStart";

	private UpdateMessages() {
	}
}