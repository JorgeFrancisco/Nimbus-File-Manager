package br.com.jorgemelo.nimbusfilemanager.database.application.dto;

/**
 * What the settings screen shows about the embedded PostgreSQL. The screen
 * renders these fields and nothing else - whether an action is offered is
 * decided here, never by the template inspecting the operating system or
 * comparing versions.
 *
 * @param installed whether the server binaries are in place
 * @param serving whether this run is actually being served by the embedded
 *                cluster, as opposed to a database configured by hand
 * @param version the major version of the installed binaries, or {@code null}
 * @param directory where the binaries live, for the screen to show
 * @param installable whether this platform can install them at all
 * @param restartRequired whether an install now would only take effect on the
 *                        next start, because the server it replaces is running
 */
public record EmbeddedDatabaseStatus(boolean installed, boolean serving, String version, String directory,
		boolean installable, boolean restartRequired) {
}