package br.com.jorgemelo.nimbusfilemanager.shared.application;

import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.WorkspaceConstants.INSTALLED_FOLDER;
import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.WorkspaceConstants.WORKSPACE_ENVIRONMENT_VARIABLE;
import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.WorkspaceConstants.WORKSPACE_FOLDER;
import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.WorkspaceConstants.WORKSPACE_PROPERTY;

import java.nio.file.Path;

/**
 * Decides where the workspace lives, once, before anything reads it.
 *
 * <p>
 * Three consumers need the same answer and none can ask the others: Logback
 * resolves {@code nimbus-file-manager.workspace} while the log file is being
 * opened, the workspace bootstrap listener creates the folders before the
 * context exists, and the workspace manager answers every path afterwards. So
 * the value is computed here and published as that single property - any of the
 * three deciding for itself is how the log ends up in one folder and the
 * database in another.
 *
 * <p>
 * It is always under the user's home, whether the copy was installed or started
 * from a build. An installation lives in a folder the user cannot write to, and
 * a workspace that cannot be created takes the application with it - but the
 * stronger reason is that one location means development exercises the very
 * layout that ships. While a build wrote beside the project instead, the
 * packaged copy was the only one that ever ran the installed layout, and it
 * took running it to find that it could not locate its own {@code pg_dump}.
 */
public final class WorkspaceLocation {

	private WorkspaceLocation() {
	}

	public static String resolve() {
		return resolve(System.getProperty(WORKSPACE_PROPERTY), System.getenv(WORKSPACE_ENVIRONMENT_VARIABLE),
				System.getProperty("user.home"));
	}

	/**
	 * Takes what it depends on so every layout can be exercised; production reads
	 * the property, the environment and the user's home.
	 *
	 * <p>
	 * The property wins over the variable so that a test run, which sets the
	 * property, can never be pointed at the workspace someone is actually using.
	 */
	static String resolve(String property, String environment, String userHome) {
		if (isSet(property)) {
			return property;
		}

		if (isSet(environment)) {
			return environment;
		}

		return Path.of(userHome, INSTALLED_FOLDER, WORKSPACE_FOLDER).toString();
	}

	/** An empty value is an unset one, not a request to write at the root. */
	private static boolean isSet(String value) {
		return value != null && !value.isBlank();
	}
}