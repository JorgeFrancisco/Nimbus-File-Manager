package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The script runs after this process is gone, so nothing it does can be
 * observed from here - which is exactly why its text is worth asserting. Each
 * line fixes a complaint from the first real update: the window vanished and
 * nothing came back, and a hundred-megabyte installer stayed in the workspace
 * forever.
 */
class UpdateInstallScriptTest {

	private static final Path INSTALLER = Path.of("C:", "workspace", "temp", "Nimbus.msi");
	private static final String LAUNCHER = "C:\\Program Files\\Nimbus File Manager\\Nimbus File Manager.exe";

	@Test
	void waitsForTheInstallerBeforeDoingAnythingElse() {
		String script = UpdateInstallScript.build(INSTALLER, LAUNCHER);

		Assertions.assertThat(script).contains("/wait").contains("msiexec /i").contains("/passive")
				.contains("/norestart");
	}

	/**
	 * Ordering is the whole contract: deleting or relaunching before the installer
	 * finishes would remove the file being read, or start the version being
	 * replaced.
	 */
	@Test
	void deletesTheInstallerAndReopensTheApplicationAfterwards() {
		String script = UpdateInstallScript.build(INSTALLER, LAUNCHER);

		int wait = script.indexOf("/wait");
		int delete = script.indexOf("del /f /q \"" + INSTALLER.toAbsolutePath());
		int relaunch = script.indexOf(LAUNCHER);

		Assertions.assertThat(wait).isLessThan(delete);
		Assertions.assertThat(delete).isLessThan(relaunch);
	}

	/** Otherwise every update leaves another installer behind in the workspace. */
	@Test
	void removesTheInstallerItJustRan() {
		Assertions.assertThat(UpdateInstallScript.build(INSTALLER, LAUNCHER))
				.contains("del /f /q \"" + INSTALLER.toAbsolutePath() + "\"");
	}

	@Test
	void removesItselfOnTheWayOut() {
		Assertions.assertThat(UpdateInstallScript.build(INSTALLER, LAUNCHER)).contains("del /f /q \"%~f0\"");
	}

	/**
	 * A run started from a build or from the IDE has no installed launcher. There
	 * is nothing to reopen, and inventing a path would open something that is not
	 * this application.
	 */
	@Test
	void reopensNothingWhenThereIsNoInstalledLauncher() {
		Assertions.assertThat(UpdateInstallScript.build(INSTALLER, null)).doesNotContain("Nimbus File Manager.exe");
		Assertions.assertThat(UpdateInstallScript.build(INSTALLER, "  ")).doesNotContain("start \"\" \"");
	}

	/**
	 * Paths with spaces are the normal case here - "Program Files" and the
	 * workspace both have them - so every one of them has to be quoted.
	 */
	@Test
	void quotesEveryPathItWrites() {
		String script = UpdateInstallScript.build(INSTALLER, LAUNCHER);

		Assertions.assertThat(script).contains("\"" + INSTALLER.toAbsolutePath() + "\"")
				.contains("\"" + LAUNCHER + "\"");
	}

	@Test
	void usesWindowsLineEndingsSoCmdCanReadIt() {
		Assertions.assertThat(UpdateInstallScript.build(INSTALLER, LAUNCHER)).contains("\r\n")
				.startsWith("@echo off\r\n");
	}
}