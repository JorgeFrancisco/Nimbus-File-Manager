package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The script runs after this process is gone, so nothing it does can be
 * observed from here - which is exactly why its text is worth asserting. Each
 * line fixes a complaint from a real update: the window vanished and nothing
 * came back, a hundred-megabyte installer stayed in the workspace forever, and
 * the application was reopened while its own files were still being replaced.
 */
class UpdateInstallScriptTest {

	private static final String LAUNCHER = "C:\\Program Files\\Nimbus File Manager\\Nimbus File Manager.exe";

	private static final String VERSION = "6.2.7";

	private String script(Path folder) {
		return script(folder, LAUNCHER);
	}

	private String script(Path folder, String launcher) {
		return UpdateInstallScript.build(folder.resolve("Nimbus.msi"), launcher, folder.resolve("install.log"),
				VERSION);
	}

	@Test
	void runsTheInstallerAndTellsItToWriteALog(@TempDir Path folder) {
		Assertions.assertThat(script(folder)).contains("msiexec /i \"" + folder.resolve("Nimbus.msi") + "\"")
				.contains("/passive").contains("/norestart")
				.contains("/l*v \"" + folder.resolve("install.log") + "\"");
	}

	/**
	 * The defect this script was rewritten for. Waiting on the {@code msiexec}
	 * that was launched waits on a process that hands the package to the Windows
	 * Installer service and returns within seconds - after which the script
	 * reopened the application on top of an installation still in progress. The
	 * wait has to watch the version Windows records, which only changes once the
	 * installation is committed.
	 */
	@Test
	void waitsForWindowsToRecordTheNewVersionRatherThanForAProcess(@TempDir Path folder) {
		String script = script(folder);

		Assertions.assertThat(script).contains("DisplayVersion -eq '" + VERSION + "'")
				.contains("DisplayName -eq 'Nimbus File Manager'").doesNotContain("start \"\" /wait msiexec");
	}

	/**
	 * A wait with no way out would leave the application closed for good on a
	 * machine where the installation never completes. Coming back on the old
	 * version beats not coming back.
	 */
	@Test
	void givesUpWaitingAfterADeadlineSoTheApplicationAlwaysReopens(@TempDir Path folder) {
		String script = script(folder);

		Assertions.assertThat(script).contains("AddMinutes(10)").contains("while ((Get-Date) -lt $deadline)");

		Assertions.assertThat(script.indexOf("$deadline")).isLessThan(script.indexOf(LAUNCHER));
	}

	/**
	 * Ordering is the whole contract: deleting or relaunching before the installer
	 * finishes would remove the file being read, or start the version being
	 * replaced.
	 */
	@Test
	void deletesTheInstallerAndReopensTheApplicationAfterTheWait(@TempDir Path folder) {
		String script = script(folder);

		int wait = script.indexOf("powershell");
		int delete = script.indexOf("del /f /q \"" + folder.resolve("Nimbus.msi"));
		int relaunch = script.indexOf(LAUNCHER);

		Assertions.assertThat(wait).isLessThan(delete);
		Assertions.assertThat(delete).isLessThan(relaunch);
	}

	/**
	 * An installer that failed is the one thing that explains why, so it stays
	 * beside its log. Both codes count as success: 3010 is an installation that
	 * worked and asked for a restart.
	 */
	@Test
	void removesTheInstallerOnlyWhenTheInstallerReportedSuccess(@TempDir Path folder) {
		String script = script(folder);

		Assertions.assertThat(script).contains("set NIMBUS_MSI_EXIT=%errorlevel%")
				.contains("if \"%NIMBUS_MSI_EXIT%\"==\"0\" del /f /q \"" + folder.resolve("Nimbus.msi") + "\"")
				.contains("if \"%NIMBUS_MSI_EXIT%\"==\"3010\" del /f /q \"" + folder.resolve("Nimbus.msi") + "\"");

		Assertions.assertThat(script.indexOf("set NIMBUS_MSI_EXIT")).isLessThan(script.indexOf("powershell"));
	}

	/** Otherwise every update leaves a script and a wrapper behind. */
	@Test
	void removesItselfAndItsWrapperOnTheWayOut(@TempDir Path folder) {
		Assertions.assertThat(script(folder)).contains("del /f /q \"%~dpn0.vbs\"")
				.contains("del /f /q \"%~f0\"");
	}

	/**
	 * A run started from a build or from the IDE has no installed launcher. There
	 * is nothing to reopen, and inventing a path would open something that is not
	 * this application.
	 */
	@Test
	void reopensNothingWhenThereIsNoInstalledLauncher(@TempDir Path folder) {
		Assertions.assertThat(script(folder, null)).doesNotContain("Nimbus File Manager.exe");
		Assertions.assertThat(script(folder, "  ")).doesNotContain("start \"\" \"");
	}

	/**
	 * Paths with spaces are the normal case here - "Program Files" and the
	 * workspace both have them - so every one of them has to be quoted.
	 */
	@Test
	void quotesEveryPathItWrites(@TempDir Path folder) {
		String script = script(folder);

		Assertions.assertThat(script).contains("\"" + folder.resolve("Nimbus.msi") + "\"")
				.contains("\"" + folder.resolve("install.log") + "\"").contains("\"" + LAUNCHER + "\"");
	}

	@Test
	void usesWindowsLineEndingsSoCmdCanReadIt(@TempDir Path folder) {
		Assertions.assertThat(script(folder)).contains("\r\n").startsWith("@echo off\r\n");
	}

	/**
	 * The wrapper is what keeps the installation out of a console window - and it
	 * has to wait rather than return at once, or the script would be orphaned the
	 * moment the host exited.
	 */
	@Test
	void wrapsTheScriptInAHostThatShowsNoWindowAndWaitsForIt(@TempDir Path folder) {
		Path file = folder.resolve("nimbus-update.cmd");

		Assertions.assertThat(UpdateInstallScript.wrapper(file))
				.isEqualTo("WScript.Quit CreateObject(\"WScript.Shell\").Run(\"\"\"" + file + "\"\"\", 0, True)\r\n");
	}

	/**
	 * Asserting the text is not enough for this one line: three levels of quoting
	 * meet in it - Java, VBScript and the command line - and getting one of them
	 * wrong produces a wrapper that still looks right and silently runs nothing.
	 * So it is run, against a script whose only job is to answer with a code
	 * nobody could produce by accident.
	 *
	 * <p>
	 * Windows only, and skipped elsewhere rather than failed: the host it needs
	 * ships with the operating system this installs on, and the CI runner is
	 * Linux.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void theWrapperRunsTheScriptAndAnswersWithItsExitCode(@TempDir Path folder) throws Exception {
		Path script = folder.resolve("exits-with-seven.cmd");
		Path wrapper = folder.resolve("exits-with-seven.vbs");

		Files.writeString(script, "@echo off\r\nexit /b 7\r\n");

		Files.writeString(wrapper, UpdateInstallScript.wrapper(script));

		Path host = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "wscript.exe");

		Process process = new ProcessBuilder(host.toString(), "//B", "//Nologo", wrapper.toString()).start();

		Assertions.assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();

		Assertions.assertThat(process.exitValue()).isEqualTo(7);
	}
}