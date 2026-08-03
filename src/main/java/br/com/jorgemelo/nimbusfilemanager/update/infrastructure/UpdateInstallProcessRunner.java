package br.com.jorgemelo.nimbusfilemanager.update.infrastructure;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateInstallScript;
import lombok.extern.slf4j.Slf4j;

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
 * It is started through {@code wscript}, a GUI host, so that no console window
 * is created for it. Started as a console script it left a black window on the
 * taskbar for the whole installation - and one that stayed there when an
 * installation got stuck, looking like leftover rubbish worth closing, when
 * closing it is what strands the update.
 *
 * <p>
 * The files are written in the console's own charset rather than UTF-8. The
 * paths they contain go through {@code cmd}, which reads a file in the OEM code
 * page, and a user name with an accent in it would otherwise arrive corrupted -
 * the installer would not be found, and the update would fail on exactly the
 * machines whose owner spells their name in Portuguese.
 */
@Slf4j
@Component
public class UpdateInstallProcessRunner {

	private static final String SCRIPT_NAME = "nimbus-update";

	/**
	 * @param installer the verified installer, in a folder this application owns
	 * @param installLog where the installer is told to write what it did
	 * @param productVersion the version the script waits for Windows to record
	 */
	public void start(Path installer, Path installLog, String productVersion) throws IOException {
		Path script = installer.resolveSibling(SCRIPT_NAME + ".cmd");
		Path wrapper = installer.resolveSibling(SCRIPT_NAME + ".vbs");

		Charset charset = Charset.forName(System.getProperty("sun.jnu.encoding", "windows-1252"));

		Files.createDirectories(installLog.getParent());

		Files.writeString(script, UpdateInstallScript.build(installer, System.getProperty("jpackage.app-path"),
				installLog, productVersion), charset);

		Files.writeString(wrapper, UpdateInstallScript.wrapper(script), charset);

		new ProcessBuilder(command(script, wrapper)).start();
	}

	/**
	 * The hidden host when it is there, and the plain console script when it is
	 * not - a policy that strips the Windows Script Host, or a hardened image. A
	 * visible window is a far better outcome than an update that cannot start at
	 * all.
	 *
	 * <p>
	 * Both hosts by absolute path rather than by name: what runs through here
	 * installs the application over itself, which is the last place to accept
	 * whatever a writable PATH entry answers for.
	 */
	private List<String> command(Path script, Path wrapper) {
		Path system = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32");
		Path host = system.resolve("wscript.exe");

		if (Files.isRegularFile(host)) {
			return List.of(host.toString(), "//B", "//Nologo", wrapper.toAbsolutePath().toString());
		}

		log.debug("The Windows Script Host is not available; the installation will run with a window");

		return List.of(system.resolve("cmd.exe").toString(), "/c", "start", "", "/min",
				script.toAbsolutePath().toString());
	}
}