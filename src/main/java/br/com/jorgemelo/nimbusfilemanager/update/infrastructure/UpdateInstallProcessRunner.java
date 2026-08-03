package br.com.jorgemelo.nimbusfilemanager.update.infrastructure;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Starts the verified installer and returns, leaving it running after this
 * process is gone.
 *
 * <p>
 * The MSI replaces the files this very run is executing from, so it cannot
 * finish while the application is up - which is why the caller ends the run
 * immediately after this returns, through the same graceful shutdown the tray
 * uses, so the embedded PostgreSQL is stopped rather than left behind.
 *
 * <p>
 * {@code msiexec} is invoked rather than the file being opened, and passive
 * rather than silent: the person asked for the update and should see it
 * happening, but should not have to answer the same questions they answered
 * when they installed it the first time. It raises the elevation prompt itself,
 * which is what keeps this from depending on how the application was started.
 */
@Component
public class UpdateInstallProcessRunner {

	private static final String MSIEXEC = "msiexec";

	public void start(Path installer) throws IOException {
		List<String> command = List.of(MSIEXEC, "/i", installer.toAbsolutePath().toString(), "/passive", "/norestart");

		new ProcessBuilder(command).start();
	}
}