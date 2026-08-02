package br.com.jorgemelo.nimbusfilemanager.shared.application.constants;

/**
 * Where a run keeps what it writes, and where it looks for the tools it spawns.
 * Read by more than one domain - the workspace, the external tools and the
 * embedded database all resolve against these - so they are contract rather
 * than a private detail of any of them.
 */
public final class WorkspaceConstants {

	public static final String WORKSPACE_PROPERTY = "nimbus-file-manager.workspace";

	/**
	 * Points the external tools somewhere other than the workspace: a copy already
	 * installed on the machine, or - for the test run - the developer's real one,
	 * so the suite can exercise {@code pg_dump} without writing into it.
	 */
	public static final String TOOLS_PROPERTY = "nimbus-file-manager.tools";

	/** Read by the container, the compose file and anyone scripting a run. */
	public static final String WORKSPACE_ENVIRONMENT_VARIABLE = "NIMBUS_FILE_MANAGER_WORKSPACE";

	public static final String INSTALLED_FOLDER = "Nimbus File Manager";
	public static final String WORKSPACE_FOLDER = "workspace";

	private WorkspaceConstants() {
	}
}