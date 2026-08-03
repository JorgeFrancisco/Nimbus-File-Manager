package br.com.jorgemelo.nimbusfilemanager.update.infrastructure;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateInstallScript;

/**
 * Writes the install script and starts it detached, so it outlives this
 * process.
 *
 * <p>
 * Detached is the whole point: the MSI replaces the files this run is executing
 * from, so the run has to end for it to succeed - and something has to be alive
 * afterwards to delete the installer and open the application again.
 *
 * <p>
 * The script is written in the console's own charset rather than UTF-8. The
 * paths it contains go through {@code cmd}, which reads a file in the OEM code
 * page, and a user name with an accent in it would otherwise arrive corrupted -
 * the installer would not be found, and the update would fail on exactly the
 * machines whose owner spells their name in Portuguese.
 */
@Component
public class UpdateInstallProcessRunner {

	private static final String SCRIPT_NAME = "nimbus-update.cmd";

	public void start(Path installer) throws IOException {
		Path script = installer.resolveSibling(SCRIPT_NAME);

		Files.writeString(script, UpdateInstallScript.build(installer, System.getProperty("jpackage.app-path")),
				Charset.forName(System.getProperty("sun.jnu.encoding", "windows-1252")));

		List<String> command = List.of(shell(), "/c", "start", "", "/min", script.toAbsolutePath().toString());

		new ProcessBuilder(command).start();
	}

	/**
	 * The interpreter by absolute path rather than by name. Looking {@code cmd} up
	 * through PATH lets whatever the first writable entry there points at run in
	 * its place - and what runs here installs the application over itself, which
	 * is the last place to accept a name somebody else can answer for.
	 */
	private static String shell() {
		return Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "cmd.exe").toString();
	}
}