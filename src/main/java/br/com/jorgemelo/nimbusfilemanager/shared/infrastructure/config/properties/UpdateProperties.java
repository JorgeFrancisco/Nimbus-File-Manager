package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The update check (prefix {@code nimbus-file-manager.update}).
 *
 * <p>
 * This is the first thing in the application that talks to the internet without
 * the user having asked for anything. Every other download - ffmpeg, the
 * database server, the geographic dataset - happens because a feature needs it;
 * this one happens on its own, periodically. So it is switchable, and switching
 * it off is a first-class answer rather than a workaround: an installation that
 * never wants to phone home sets {@code enabled} to false and nothing here ever
 * opens a connection.
 *
 * <p>
 * The address is configuration rather than a constant for the same reason the
 * ffmpeg and PostgreSQL downloads take one: a relocated or mirrored release can
 * be pointed at without a new version of the application. It is deliberately
 * not a setting on the screen - a value stored in the catalog travels inside a
 * backup and would land describing a machine it was never taken on, which is
 * exactly the bug the external tool paths were moved out of the catalog to fix.
 *
 * @param enabled whether the check may run at all (default true)
 * @param releaseUrl the endpoint answering with the most recent release
 */
@ConfigurationProperties(prefix = "nimbus-file-manager.update")
public record UpdateProperties(@DefaultValue("true") boolean enabled,
		@DefaultValue("https://api.github.com/repos/JorgeFrancisco/Nimbus-File-Manager/releases/latest")
		String releaseUrl) {
}