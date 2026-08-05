package br.com.jorgemelo.nimbusfilemanager.shared.application;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;

/**
 * Which role this JVM was started in, read from the command line because the
 * question is asked before Spring exists.
 *
 * <p>
 * Almost everything that belongs to one role is a {@code @Profile} on a bean,
 * which is the right answer and needs no help. The exception is what
 * {@code main} does on its way to {@code SpringApplication.run} - the tray icon
 * - and there is no environment to ask yet. So the same question is asked of
 * the arguments the launcher passed.
 *
 * <p>
 * The rule is the one {@code EmbeddedDatabaseBootstrap} asks of the resolved
 * environment, {@code worker & !app}: named as a worker, not named as an
 * application. Asked this way round on purpose. A profile group is expanded by
 * Spring and not by anyone reading argv, so {@code app-worker-combined} is one
 * opaque name here rather than the two roles it becomes - and the answer it
 * gets, "not a standalone worker", is the correct one. It is also the safe
 * direction to be wrong in: an unrecognised profile leaves the tray installed,
 * which is visible and harmless, where the opposite would silently take away
 * the only thing this application shows.
 *
 * <p>
 * Matching by name and never by substring is the whole point:
 * {@code app-worker-combined} contains the word.
 */
public final class StartupRole {

	private static final String ACTIVE_PROPERTY = "spring.profiles.active";
	private static final String ACTIVE_ARGUMENT = "--" + ACTIVE_PROPERTY + "=";

	private StartupRole() {
	}

	/**
	 * Whether this process was started to be nothing but a worker.
	 */
	public static boolean isStandaloneWorker(String[] arguments) {
		return isStandaloneWorker(arguments, System.getProperty(ACTIVE_PROPERTY));
	}

	/**
	 * The argument wins over the system property, as it does in Spring, so a
	 * worker launched by the supervisor is a worker whatever the machine was
	 * configured with.
	 */
	static boolean isStandaloneWorker(String[] arguments, String property) {
		String configured = fromArguments(arguments);

		Set<String> named = namesIn(configured == null ? property : configured);

		return named.contains(NimbusProfiles.WORKER) && !named.contains(NimbusProfiles.APP);
	}

	/** The last occurrence wins, which is how Spring reads a repeated argument. */
	private static String fromArguments(String[] arguments) {
		String configured = null;

		for (String argument : arguments) {
			if (argument.startsWith(ACTIVE_ARGUMENT)) {
				configured = argument.substring(ACTIVE_ARGUMENT.length());
			}
		}

		return configured;
	}

	private static Set<String> namesIn(String profiles) {
		if (profiles == null || profiles.isBlank()) {
			return Set.of();
		}

		return Arrays.stream(profiles.split(",")).map(String::trim).filter(name -> !name.isEmpty())
				.map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
	}
}