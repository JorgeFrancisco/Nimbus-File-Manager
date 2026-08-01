package br.com.jorgemelo.nimbusfilemanager.database.application;

import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants.SUPPORTED_MAJOR_VERSION;

import java.nio.file.Path;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.database.application.dto.EmbeddedDatabaseStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.application.CoverageGenerated;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;

/**
 * The settings screen's view of the embedded database, and the button that
 * installs it.
 *
 * <p>
 * The automatic install happens before the context exists, so it cannot use
 * this; what it can do is answer the screen afterwards and let an operator
 * fetch the server on a run that did not. Both paths share
 * {@link EmbeddedDatabaseInstaller}, so there is one place that knows what to
 * keep out of the archive.
 */
@Service
public class EmbeddedDatabaseAdminService {

	private static final String WINDOWS = "windows";

	private final ClusterLayout layout;
	private final PostgresArchiveSource archiveSource;
	private final String operatingSystem;

	@Autowired
	@CoverageGenerated("Spring wiring: forwards to the constructor every test builds directly")
	public EmbeddedDatabaseAdminService(WorkspaceManager workspaceManager, PostgresArchiveSource archiveSource) {
		this(new ClusterLayout(workspaceManager.getWorkspacePath()), archiveSource, System.getProperty("os.name"));
	}

	/** Takes what it depends on so a test can describe any machine. */
	EmbeddedDatabaseAdminService(ClusterLayout layout, PostgresArchiveSource archiveSource, String operatingSystem) {
		this.layout = layout;
		this.archiveSource = archiveSource;
		this.operatingSystem = operatingSystem;
	}

	/**
	 * @param serving whether the running application is being served by the
	 *                embedded cluster rather than by a database configured by
	 *                hand - which is also what decides whether an install now
	 *                would have to wait for the next start
	 */
	public EmbeddedDatabaseStatus status(boolean serving) {
		return new EmbeddedDatabaseStatus(layout.binariesPresent(), serving, installedVersion(), directory(),
				installable(), serving);
	}

	/**
	 * Fetches or replaces the server.
	 *
	 * <p>
	 * Nothing here refuses to run while the cluster is up, because on Windows
	 * the executables in use simply cannot be overwritten and the copy fails on
	 * its own. What the screen has to say instead is that whatever was replaced
	 * only takes effect on the next start.
	 */
	public boolean install() {
		return installable() && new EmbeddedDatabaseInstaller(layout, archiveSource).install();
	}

	private String directory() {
		Path folder = layout.serverFolder();

		return folder.toAbsolutePath().toString();
	}

	/**
	 * The version the installed binaries were built for, taken from the pinned
	 * constant rather than by running {@code postgres --version}: the build
	 * already decided which major version it opens, and spawning a process to
	 * ask would only add a way for the two to disagree.
	 */
	private String installedVersion() {
		return layout.binariesPresent() ? SUPPORTED_MAJOR_VERSION : null;
	}

	private boolean installable() {
		return operatingSystem != null && operatingSystem.toLowerCase(Locale.ROOT).contains(WINDOWS);
	}
}