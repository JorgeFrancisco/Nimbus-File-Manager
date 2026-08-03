package br.com.jorgemelo.nimbusfilemanager.update.application.dto;

/**
 * What the settings screen shows about updates.
 *
 * <p>
 * The decisions arrive made. The screen renders these fields and never works
 * out for itself whether a button belongs there - not by comparing versions,
 * not by reading the operating system.
 *
 * @param installed the version this run is, or {@code null} when it has none,
 * which is every run outside a packaged build
 * @param available whether a newer release was found
 * @param published the version that was found, or {@code null}
 * @param page where that release can be read about, or {@code null}
 * @param canCheck whether checking is allowed at all
 * @param canInstall whether this installation can install what was found
 */
public record UpdateStatus(String installed, boolean available, String published, String page, boolean canCheck,
		boolean canInstall) {
}