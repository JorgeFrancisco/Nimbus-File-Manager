package br.com.jorgemelo.nimbusfilemanager.shared.application.constants;

/**
 * How a run describes itself: where the workspace goes, and whether this copy
 * was installed or started from a build. The installation marker is read by
 * more than one domain - the workspace decides its own location from it, and
 * the embedded database decides whether it should run at all - so it is
 * contract rather than a private detail of either.
 */
public final class WorkspaceConstants {

	public static final String WORKSPACE_PROPERTY = "nimbus-file-manager.workspace";

	/** Read by the container, the compose file and anyone scripting a run. */
	public static final String WORKSPACE_ENVIRONMENT_VARIABLE = "NIMBUS_FILE_MANAGER_WORKSPACE";

	/**
	 * Set by the launcher of a jpackage image and by nothing else, which is how
	 * the application tells an installation from a build.
	 */
	public static final String INSTALLED_MARKER = "jpackage.app-path";

	public static final String INSTALLED_FOLDER = "Nimbus File Manager";
	public static final String DEVELOPMENT_WORKSPACE = "./workspace";

	private WorkspaceConstants() {
	}
}