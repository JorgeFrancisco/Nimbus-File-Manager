package br.com.jorgemelo.nimbusfilemanager.shared.application;

import static br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceConstants.TOOLS_PROPERTY;
import static br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceFolders.TOOLS;

import java.nio.file.Path;

/**
 * Where the external tools live: {@code <workspace>/tools/<tool>/bin}, always.
 *
 * <p>
 * They are downloaded rather than shipped, so they are the application's data
 * and not part of the application: an installation may sit in a folder nobody
 * can write to, and hundreds of megabytes arriving at runtime have to land
 * somewhere that is writable by definition. The workspace already is, for the
 * same reason the catalog lives there.
 *
 * <p>
 * One rule for every mode. The path used to be relative to the working
 * directory, which is the project root in a build and, in an installation,
 * whatever folder the shortcut was started from - so a packaged copy looked for
 * its own {@code pg_dump} where nothing had ever been written, and downloaded
 * ffmpeg next to whoever launched it.
 */
public final class ToolsLocation {

	private static final String BINARY_FOLDER = "bin";

	private ToolsLocation() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	/** For callers that already hold the workspace, which is most of them. */
	public static Path of(Path workspace, String tool) {
		return root(workspace).resolve(tool).resolve(BINARY_FOLDER);
	}

	/**
	 * The workspace owns the tools unless someone says otherwise. The override
	 * exists for a machine that already has them installed, and for the test run,
	 * which needs to reach a real {@code pg_dump} while writing its own workspace
	 * somewhere disposable.
	 */
	private static Path root(Path workspace) {
		String configured = System.getProperty(TOOLS_PROPERTY);

		return configured == null || configured.isBlank() ? workspace.resolve(TOOLS) : Path.of(configured);
	}

	/**
	 * For beans that would otherwise reach for the workspace manager: the answer
	 * comes from {@link WorkspaceLocation}, the same source the log file and the
	 * database already use, rather than from a fourth opinion.
	 */
	public static Path of(String tool) {
		return of(Path.of(WorkspaceLocation.resolve()), tool);
	}
}