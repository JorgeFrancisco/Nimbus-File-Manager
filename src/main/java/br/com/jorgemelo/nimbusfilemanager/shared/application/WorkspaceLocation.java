package br.com.jorgemelo.nimbusfilemanager.shared.application;

import static br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceConstants.DEVELOPMENT_WORKSPACE;
import static br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceConstants.INSTALLED_FOLDER;
import static br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceConstants.INSTALLED_MARKER;
import static br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceConstants.WORKSPACE_ENVIRONMENT_VARIABLE;

import java.nio.file.Path;

/**
 * Decides where the workspace lives, once, before anything reads it.
 *
 * <p>
 * Three consumers need the same answer and none can ask the others: Logback
 * resolves {@code nimbus-file-manager.workspace} while the log file is being
 * opened, the workspace bootstrap listener creates the folders before the
 * context exists, and the workspace manager answers every path afterwards.
 * So the value is computed here and published as that single property - any of
 * the three deciding for itself is how the log ends up in one folder and the
 * database in another.
 *
 * <p>
 * An installed copy writes under the user's home instead of next to the
 * executable: an installation lives in a folder the user cannot write to, and a
 * workspace that cannot be created takes the application with it. Started from
 * a build, the relative default keeps everything beside the project, which is
 * what development expects.
 */
public final class WorkspaceLocation {

	private WorkspaceLocation() {
	}

	public static String resolve() {
		return resolve(System.getenv(WORKSPACE_ENVIRONMENT_VARIABLE), System.getProperty(INSTALLED_MARKER),
				System.getProperty("user.home"));
	}

	/**
	 * Takes what it depends on so every layout can be exercised; production reads
	 * the environment and the launcher. The marker arrives as the raw property
	 * rather than a boolean so that deciding what counts as installed stays here,
	 * where a test reaches it, instead of on the untestable side of the call.
	 */
	static String resolve(String configured, String installedMarker, String userHome) {
		if (configured != null && !configured.isBlank()) {
			return configured;
		}

		if (installedMarker != null) {
			return Path.of(userHome, INSTALLED_FOLDER, "workspace").toString();
		}

		return DEVELOPMENT_WORKSPACE;
	}
}