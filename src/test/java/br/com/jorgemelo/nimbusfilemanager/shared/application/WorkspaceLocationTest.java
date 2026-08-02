package br.com.jorgemelo.nimbusfilemanager.shared.application;

import static br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceConstants.INSTALLED_FOLDER;
import static br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceConstants.WORKSPACE_FOLDER;

import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Where the workspace lands, which is the one decision three different readers
 * depend on. It is the same place in every mode on purpose: while a build wrote
 * beside the project instead, the installed layout was exercised only by the
 * packaged copy, and its bugs were found by running that copy rather than by
 * any test.
 */
class WorkspaceLocationTest {

	private static final String HOME = "C:/Users/someone";

	private static final String EXPECTED = Path.of(HOME, INSTALLED_FOLDER, WORKSPACE_FOLDER).toString();

	@Test
	void putsTheWorkspaceUnderTheUserHomeWhenNothingIsConfigured() {
		Assertions.assertThat(WorkspaceLocation.resolve(null, null, HOME)).isEqualTo(EXPECTED);
	}

	/**
	 * The environment variable is how a container and the compose file point at a
	 * mounted volume, so it has to be read when no property was set.
	 */
	@Test
	void takesTheEnvironmentVariableWhenNoPropertyWasSet() {
		Assertions.assertThat(WorkspaceLocation.resolve(null, "/data/workspace", HOME)).isEqualTo("/data/workspace");
	}

	/**
	 * The property wins, and that is what keeps a test run - which sets it - from
	 * ever being pointed at the workspace someone is actually using.
	 */
	@Test
	void letsThePropertyOverrideTheEnvironmentVariable() {
		Assertions.assertThat(WorkspaceLocation.resolve("D:/nimbus/workspace", "/data/workspace", HOME))
				.isEqualTo("D:/nimbus/workspace");
	}

	/** An empty value is an unset one, not a request to write at the root. */
	@Test
	void treatsBlankValuesAsUnset() {
		Assertions.assertThat(WorkspaceLocation.resolve("   ", "  ", HOME)).isEqualTo(EXPECTED);
	}
}