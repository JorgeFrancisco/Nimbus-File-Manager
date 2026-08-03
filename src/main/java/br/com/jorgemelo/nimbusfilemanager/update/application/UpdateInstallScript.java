package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.nio.file.Path;

/**
 * Builds the script that installs the update after this process is gone.
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
 * Starting the application again is not a convenience. Without it an update
 * ends with the window gone and nothing back, so the person has to work out on
 * their own that they should go to the Start menu - which reads as a crash, not
 * as an update.
 *
 * <p>
 * Deleting the installer matters more than it looks: it is over a hundred
 * megabytes, it lives in the workspace, and nothing else would ever remove it -
 * every update would leave another one behind.
 */
public final class UpdateInstallScript {

	private UpdateInstallScript() {
	}

	/**
	 * @param installer the verified installer
	 * @param launcher the installed executable to start afterwards, or
	 * {@code null} when this run has none - a build or the IDE, where there is
	 * nothing to relaunch
	 * @return the contents of a Windows script
	 */
	public static String build(Path installer, String launcher) {
		StringBuilder script = new StringBuilder();

		script.append("@echo off\r\n");

		// Waits for the installer to finish rather than racing it: the delete and
		// the relaunch below are only correct once the files have been replaced.
		script.append("start \"\" /wait msiexec /i \"").append(installer.toAbsolutePath()).append("\" /passive")
				.append(" /norestart\r\n");

		script.append("del /f /q \"").append(installer.toAbsolutePath()).append("\"\r\n");

		if (launcher != null && !launcher.isBlank()) {
			script.append("start \"\" \"").append(launcher.trim()).append("\"\r\n");
		}

		// Deletes itself on the way out. The parenthesised goto releases the file
		// handle first, which is the only way a script can remove itself on Windows.
		script.append("(goto) 2>nul & del /f /q \"%~f0\"\r\n");

		return script.toString();
	}
}