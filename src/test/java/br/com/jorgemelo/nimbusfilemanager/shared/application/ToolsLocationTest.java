package br.com.jorgemelo.nimbusfilemanager.shared.application;

import static br.com.jorgemelo.nimbusfilemanager.shared.application.constants.ToolFolders.POSTGRESQL;
import static br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceConstants.TOOLS_PROPERTY;

import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Finding the external tools, which used to be answered in three places with
 * two different answers - and the packaged copy, resolving against the working
 * directory, looked for its own {@code pg_dump} in a folder that only ever
 * existed in a build.
 *
 * <p>
 * Isolated because the override is a system property, and the suite otherwise
 * runs concurrently in one JVM - where the build itself sets that property.
 */
@Isolated
class ToolsLocationTest {

	@Test
	void putsEveryToolUnderTheWorkspaceItWasGiven(@TempDir Path workspace) {
		withOverride(null, () -> Assertions.assertThat(ToolsLocation.of(workspace, POSTGRESQL))
				.isEqualTo(workspace.resolve("tools").resolve(POSTGRESQL).resolve("bin")));
	}

	/**
	 * The override is what lets a machine point at tools it already has - and what
	 * lets the test run reach a real {@code pg_dump} while writing its own
	 * workspace somewhere disposable.
	 */
	@Test
	void letsAConfiguredFolderReplaceTheWorkspaceOne(@TempDir Path workspace, @TempDir Path elsewhere) {
		withOverride(elsewhere.toString(), () -> Assertions.assertThat(ToolsLocation.of(workspace, POSTGRESQL))
				.isEqualTo(elsewhere.resolve(POSTGRESQL).resolve("bin")));
	}

	/** A blank override is an unset one, not a request to read from the root. */
	@Test
	void treatsABlankOverrideAsUnset(@TempDir Path workspace) {
		withOverride("   ", () -> Assertions.assertThat(ToolsLocation.of(workspace, POSTGRESQL))
				.startsWithRaw(workspace));
	}

	/**
	 * The property belongs to the whole JVM, so whatever the build set has to be
	 * put back - the classes that run next resolve their tools through it.
	 */
	private void withOverride(String value, Runnable assertion) {
		String previous = System.getProperty(TOOLS_PROPERTY);

		try {
			set(value);
			assertion.run();
		} finally {
			set(previous);
		}
	}

	private void set(String value) {
		if (value == null) {
			System.clearProperty(TOOLS_PROPERTY);
		} else {
			System.setProperty(TOOLS_PROPERTY, value);
		}
	}
}