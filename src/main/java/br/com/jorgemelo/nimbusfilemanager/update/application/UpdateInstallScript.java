package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.nio.file.Path;

/**
 * Builds the two files that install the update after this process is gone.
 *
 * <p>
 * A script rather than a direct call because three things have to happen in
 * order, and the process that would coordinate them is the one being replaced:
 * wait for the installer to finish, delete the installer, and start the
 * application again. Handing that sequence to the shell is what lets this
 * process end - which the MSI requires, since it overwrites the files this very
 * run is executing from.
 *
 * <p>
 * <b>Waiting is the part that has to be right, and the obvious way of doing it
 * is wrong.</b> {@code start /wait msiexec} waits for the {@code msiexec} it
 * launched, and that process is not the one that installs: it hands the package
 * to the Windows Installer service and returns within seconds. The script then
 * believed the installation was over, deleted the installer and reopened the
 * application - which came up while its own files were still being replaced,
 * failed to reach a database that had just been shut down, and then held open
 * the very files the installer was waiting to write. Installer waiting on the
 * application, application started by the script, script waiting on the
 * installer: an update that never finished, on a machine where nothing was
 * wrong.
 *
 * <p>
 * So the wait watches a fact instead of a process - the version Windows records
 * for the installed product, which only changes when the installation is
 * committed. The deadline exists so that an installation that never completes
 * ends with the application reopened anyway: being back on the old version
 * beats never coming back.
 *
 * <p>
 * Starting the application again is not a convenience. Without it an update
 * ends with the window gone and nothing back, so the person has to work out on
 * their own that they should go to the Start menu - which reads as a crash, not
 * as an update.
 *
 * <p>
 * Deleting the installer matters more than it looks: it is over a hundred
 * megabytes, it lives in the workspace, and nothing else would ever remove it -
 * every update would leave another one behind. It is deleted only when the
 * installer reported success, because an installer that failed is the one thing
 * that explains why, and it is worth keeping beside its log.
 */
public final class UpdateInstallScript {

	/** What {@code DisplayName} reads as, which is how the product is found. */
	private static final String PRODUCT = "Nimbus File Manager";

	private static final String UNINSTALL_KEYS = "'HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*',"
			+ "'HKLM:\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*'";

	/**
	 * Long enough for a slow disk to finish a hundred-megabyte installation, short
	 * enough that a stuck one still gives the application back the same morning.
	 */
	private static final int WAIT_MINUTES = 10;

	/** What the installer answers when it worked and asked for a restart. */
	private static final String REBOOT_REQUIRED = "3010";

	/** Closes a quoted path and ends the line, which every path here does. */
	private static final String QUOTED_END = "\"\r\n";

	private UpdateInstallScript() {
	}

	/**
	 * @param installer the verified installer
	 * @param launcher the installed executable to start afterwards, or
	 * {@code null} when this run has none - a build or the IDE, where there is
	 * nothing to relaunch
	 * @param installLog where the installer writes what it did; without it a
	 * failed update leaves nothing at all to read, which is how the first one was
	 * diagnosed by watching processes live
	 * @param productVersion the three-field version Windows will record once the
	 * installation is committed, which is what the wait watches for
	 * @return the contents of a Windows script
	 */
	public static String build(Path installer, String launcher, Path installLog, String productVersion) {
		StringBuilder script = new StringBuilder();

		script.append("@echo off\r\n");

		script.append("msiexec /i \"").append(installer.toAbsolutePath()).append("\" /passive /norestart /l*v \"")
				.append(installLog.toAbsolutePath()).append(QUOTED_END);

		// Read before anything else runs: every command overwrites it, including the
		// wait below.
		script.append("set NIMBUS_MSI_EXIT=%errorlevel%\r\n");

		script.append(wait(productVersion));

		script.append("if \"%NIMBUS_MSI_EXIT%\"==\"0\" del /f /q \"").append(installer.toAbsolutePath())
				.append(QUOTED_END);

		script.append("if \"%NIMBUS_MSI_EXIT%\"==\"").append(REBOOT_REQUIRED).append("\" del /f /q \"")
				.append(installer.toAbsolutePath()).append(QUOTED_END);

		if (launcher != null && !launcher.isBlank()) {
			script.append("start \"\" \"").append(launcher.trim()).append(QUOTED_END);
		}

		// The wrapper that hid this window has the same name with another extension,
		// so it is found without being passed in - and it is gone before the script
		// that is still running inside it removes itself.
		script.append("del /f /q \"%~dpn0.vbs\"\r\n");

		// Deletes itself on the way out. The parenthesised goto releases the file
		// handle first, which is the only way a script can remove itself on Windows.
		script.append("(goto) 2>nul & del /f /q \"%~f0\"\r\n");

		return script.toString();
	}

	/**
	 * The wrapper that runs the script with no window.
	 *
	 * <p>
	 * A packaged run is a GUI process with no console, so the script would be
	 * given a brand new one - a black window that sat on the taskbar for the whole
	 * installation and, when it stayed up because the installation was stuck,
	 * invited being closed. {@code wscript} is a GUI program itself and starts the
	 * script hidden. {@code True} makes it wait rather than return at once, so
	 * that closing nothing can orphan the installation half-way.
	 *
	 * @param script the file {@link #build} was written to
	 */
	public static String wrapper(Path script) {
		return "WScript.Quit CreateObject(\"WScript.Shell\").Run(\"\"\"" + script.toAbsolutePath() + "\"\"\", 0, True)"
				+ "\r\n";
	}

	/**
	 * Waits for Windows to record the new version, rather than for a process to
	 * end. Written for PowerShell because the registry is not readable from a
	 * batch file in any way worth maintaining, and inline because a second file on
	 * disk would be another thing to clean up. Single quotes throughout: the
	 * command travels inside a double-quoted argument.
	 */
	private static String wait(String productVersion) {
		return "powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"$deadline = (Get-Date)"
				+ ".AddMinutes(" + WAIT_MINUTES + "); do { $found = @(Get-ItemProperty " + UNINSTALL_KEYS
				+ " -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -eq '" + PRODUCT
				+ "' }); if ($found.Count -gt 0 -and $found[0].DisplayVersion -eq '" + productVersion
				+ "') { break }; Start-Sleep -Seconds 2 } while ((Get-Date) -lt $deadline)\"\r\n";
	}
}