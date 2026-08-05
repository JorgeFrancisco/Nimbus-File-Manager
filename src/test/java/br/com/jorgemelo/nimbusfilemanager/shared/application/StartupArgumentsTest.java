package br.com.jorgemelo.nimbusfilemanager.shared.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The guard against a command line that arrived broken.
 *
 * <p>
 * It catches the failure that was seen because a path split on spaces always
 * leaves fragments, and a fragment is never something this application is
 * passed. It cannot catch a whole, well-formed value that happens to be the
 * wrong one - correct quoting is the guarantee, and this is the second line.
 */
class StartupArgumentsTest {

	@Test
	void acceptsTheOptionsTheApplicationIsActuallyGiven() {
		Assertions.assertThatCode(() -> StartupArguments.requireOptionsOnly(new String[] {
				"--nimbus-file-manager.workspace=C:\\Users\\jorge\\Nimbus File Manager\\workspace",
				"--usn-elevation-attempted" })).doesNotThrowAnyException();
	}

	@Test
	void acceptsHavingBeenGivenNothing() {
		Assertions.assertThatCode(() -> StartupArguments.requireOptionsOnly(new String[0]))
			.doesNotThrowAnyException();
	}

	/**
	 * The exact shape the workspace arrived in when it crossed the elevated restart
	 * unquoted, and the reason this exists: read as it stands, the first argument
	 * names a workspace that is not the one anybody chose.
	 */
	@Test
	void refusesAPathThatArrivedSplitIntoFragments() {
		Assertions
			.assertThatThrownBy(() -> StartupArguments.requireOptionsOnly(
					new String[] { "--nimbus-file-manager.workspace=C:\\Users\\jorge\\Nimbus", "File",
							"Manager\\workspace", "--usn-elevation-attempted" }))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("File");
	}

	/** An empty argument is nothing to complain about, and names no fragment. */
	@Test
	void ignoresAnEmptyArgument() {
		Assertions.assertThatCode(() -> StartupArguments.requireOptionsOnly(new String[] { "", "--a=b" }))
			.doesNotThrowAnyException();
	}
}