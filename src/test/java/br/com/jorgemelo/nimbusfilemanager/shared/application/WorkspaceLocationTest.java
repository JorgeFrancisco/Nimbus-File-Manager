package br.com.jorgemelo.nimbusfilemanager.shared.application;

import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Where the workspace lands, which is the one decision three different readers
 * depend on. An installed copy sits in a folder the user cannot write to, so
 * the relative default that serves development would make the application fail
 * on its first log line - and that failure looks like anything but a permission
 * problem.
 */
class WorkspaceLocationTest {

	private static final String HOME = "C:/Users/someone";

	private static final String LAUNCHER = "C:/Program Files/Nimbus File Manager/Nimbus File Manager.exe";

	@Test
	void keepsTheWorkspaceBesideTheProjectWhenStartedFromABuild() {
		Assertions.assertThat(WorkspaceLocation.resolve(null, null, HOME))
				.isEqualTo(WorkspaceLocation.DEVELOPMENT_WORKSPACE);
	}

	@Test
	void movesTheWorkspaceUnderTheUserHomeWhenInstalled() {
		Assertions.assertThat(WorkspaceLocation.resolve(null, LAUNCHER, HOME))
				.isEqualTo(Path.of(HOME, WorkspaceLocation.INSTALLED_FOLDER, "workspace").toString());
	}

	/**
	 * A configured path is a decision already taken - a second disk, a shared
	 * folder, the container's volume - and being installed does not override it.
	 */
	@Test
	void respectsAConfiguredPathInEitherLayout() {
		Assertions.assertThat(WorkspaceLocation.resolve("D:/nimbus/workspace", LAUNCHER, HOME))
				.isEqualTo("D:/nimbus/workspace");
		Assertions.assertThat(WorkspaceLocation.resolve("D:/nimbus/workspace", null, HOME))
				.isEqualTo("D:/nimbus/workspace");
	}

	/** An empty variable is an unset one, not a request to write at the root. */
	@Test
	void treatsABlankConfiguredPathAsUnset() {
		Assertions.assertThat(WorkspaceLocation.resolve("   ", null, HOME))
				.isEqualTo(WorkspaceLocation.DEVELOPMENT_WORKSPACE);
	}
}