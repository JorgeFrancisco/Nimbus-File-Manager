package br.com.jorgemelo.nimbusfilemanager.database.application;

import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.desktop.ApplicationTray;

/**
 * Says what is happening while the database is being fetched and started.
 *
 * <p>
 * This runs inside an {@code EnvironmentPostProcessor}, before Logback is
 * configured, so a logger here writes nowhere - which is exactly how a several
 * hundred MB download once ran to completion with the application appearing
 * frozen and not one line to say why. The console is the only channel that
 * exists this early, and a first start that pauses for minutes has to say so.
 */
public final class BootstrapProgress {

	private static final String PREFIX = "Nimbus: ";

	private BootstrapProgress() {
	}

	public static void say(String message) {
		System.out.println(PREFIX + message);

		// The same message on the tray icon. Standard output needs a console window
		// to be seen, and the first start - minutes of downloading before anything
		// listens - is exactly when nobody has one open.
		ApplicationTray.status(message);
	}
}