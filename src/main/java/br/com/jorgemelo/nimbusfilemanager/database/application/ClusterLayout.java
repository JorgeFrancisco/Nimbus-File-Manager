package br.com.jorgemelo.nimbusfilemanager.database.application;

import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants.CLUSTER_FOLDER;
import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants.CLUSTER_PROPERTIES;
import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants.VERSION_FILE;
import static br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceConstants.INSTALLED_MARKER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceFolders;

/**
 * Where the embedded cluster and its binaries live.
 *
 * <p>
 * Built from a workspace path rather than injected with the workspace manager
 * because the decision to start a cluster happens before the context exists -
 * the connection has to be published in time for the {@code DataSource} to use
 * it - so this cannot depend on a bean.
 *
 * <p>
 * The data folder sits under the workspace's {@code database} folder, next to
 * everything else the application owns, so that a user backing up the workspace
 * backs up the catalog with it. It is also the one folder in there that no
 * cleanup may touch: see {@link ClusterProtection}.
 */
public final class ClusterLayout {

	private static final String BUNDLED_FOLDER = "tools";
	private static final String SERVER_FOLDER = "postgresql";
	private static final String BINARY_FOLDER = "bin";

	/** Where jpackage puts everything it was given as input. */
	private static final String APPLICATION_FOLDER = "app";

	private final Path workspace;
	private final Path binaries;

	public ClusterLayout(Path workspace) {
		this(workspace, bundledBinaries(System.getProperty(INSTALLED_MARKER)));
	}

	/** Takes the binary folder so a test can point at one it controls. */
	ClusterLayout(Path workspace, Path binaries) {
		this.workspace = workspace;
		this.binaries = binaries;
	}

	/** PGDATA: the cluster itself, and the only irreplaceable folder here. */
	public Path cluster() {
		return workspace.resolve(WorkspaceFolders.DATABASE).resolve(CLUSTER_FOLDER).normalize();
	}

	/**
	 * The port and generated password, kept beside the cluster rather than in it
	 * - {@code initdb} owns the contents of PGDATA, and a stray file there is one
	 * more thing a future version of PostgreSQL could object to.
	 */
	public Path clusterProperties() {
		return workspace.resolve(WorkspaceFolders.DATABASE).resolve(CLUSTER_PROPERTIES).normalize();
	}

	/**
	 * Where the packaged server is, which is not the same place in a build and
	 * in an installation.
	 *
	 * <p>
	 * A relative path is resolved against the working directory, and an
	 * installed copy is started from wherever its shortcut happens to point -
	 * the Desktop, {@code C:\Windows\System32}, anywhere. The launcher's own
	 * path is the one thing that always says where the installation is, so when
	 * the marker is there the binaries are found beside it rather than beside
	 * whoever started it.
	 */
	static Path bundledBinaries(String installedMarker) {
		Path relative = Path.of(BUNDLED_FOLDER, SERVER_FOLDER, BINARY_FOLDER);

		if (installedMarker == null || installedMarker.isBlank()) {
			return relative;
		}

		Path launcher = Path.of(installedMarker).toAbsolutePath().getParent();

		return launcher == null ? relative : launcher.resolve(APPLICATION_FOLDER).resolve(relative);
	}

	/** Where the server is unpacked: the folder holding {@code bin}. */
	public Path serverFolder() {
		Path parent = binaries.getParent();

		return parent == null ? binaries : parent;
	}

	public Path executable(String name) {
		return binaries.resolve(name + ".exe");
	}

	/** Whether the packaged server is where it belongs. */
	public boolean binariesPresent() {
		return Files.isRegularFile(executable("pg_ctl")) && Files.isRegularFile(executable("initdb"))
				&& Files.isRegularFile(executable("postgres"));
	}

	/** Whether {@code initdb} has already run here. */
	public boolean initialized() {
		return Files.isRegularFile(cluster().resolve(VERSION_FILE));
	}

	/**
	 * The major version the existing data belongs to, or {@code null} when there
	 * is no cluster yet. Opening data written by another major version is what
	 * PostgreSQL refuses to do, so this is read before starting rather than
	 * discovered from a failure afterwards.
	 */
	public String majorVersion() throws IOException {
		Path version = cluster().resolve(VERSION_FILE);

		if (!Files.isRegularFile(version)) {
			return null;
		}

		return Files.readString(version).trim();
	}
}