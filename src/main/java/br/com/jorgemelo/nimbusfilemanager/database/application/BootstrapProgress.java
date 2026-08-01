package br.com.jorgemelo.nimbusfilemanager.database.application;

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
	}
}