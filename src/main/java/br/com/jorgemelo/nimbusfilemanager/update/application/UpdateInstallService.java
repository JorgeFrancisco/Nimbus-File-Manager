package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.shared.application.ApplicationShutdown;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceFolders;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.AvailableUpdate;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.PreparedInstaller;
import br.com.jorgemelo.nimbusfilemanager.update.domain.enums.UpdateOutcome;
import br.com.jorgemelo.nimbusfilemanager.update.infrastructure.UpdateInstallProcessRunner;
import lombok.extern.slf4j.Slf4j;

/**
 * Installs the update that was found, and ends this run so it can be applied.
 *
 * <p>
 * The download goes to the workspace like everything else the application
 * writes: an installation may sit in a folder nobody can write to, and a
 * hundred-megabyte file is the user's data rather than part of the program.
 *
 * <p>
 * Ending the run is not a detail of the installation, it is part of it. The MSI
 * replaces the files this process is executing from, so it cannot complete
 * while the process is alive. That ending goes through {@link
 * ApplicationShutdown} rather than being done here, which is what makes the
 * decisions around it assertable: a test can prove that a mismatched checksum
 * leaves the run alive, and that a verified installer ends it, without the
 * suite exiting.
 */
@Slf4j
@Service
public class UpdateInstallService {

	private static final String WINDOWS = "windows";

	private final UpdateCheckService updateCheckService;
	private final ReleaseDownloader releaseDownloader;
	private final UpdateInstallProcessRunner processRunner;
	private final WorkspaceManager workspaceManager;
	private final ApplicationShutdown applicationShutdown;

	public UpdateInstallService(UpdateCheckService updateCheckService, ReleaseDownloader releaseDownloader,
			UpdateInstallProcessRunner processRunner, WorkspaceManager workspaceManager,
			ApplicationShutdown applicationShutdown) {
		this.updateCheckService = updateCheckService;
		this.releaseDownloader = releaseDownloader;
		this.processRunner = processRunner;
		this.workspaceManager = workspaceManager;
		this.applicationShutdown = applicationShutdown;
	}

	/**
	 * Whether asking to install would get anywhere, which is what decides that
	 * the screen offers the action at all. Both halves matter: there has to be
	 * something to install, and the installer is an MSI.
	 */
	public boolean canInstall() {
		return updateCheckService.available().isPresent() && installable();
	}

	/**
	 * Fetches, verifies and starts the installer for whatever the last check
	 * found.
	 *
	 * @return what happened, which the caller turns into a message; anything
	 * other than {@link UpdateOutcome#STARTED} means nothing was installed and
	 * this run continues
	 */
	public UpdateOutcome install() {
		Optional<AvailableUpdate> update = updateCheckService.available();

		if (update.isEmpty()) {
			return UpdateOutcome.NOTHING_TO_INSTALL;
		}

		if (!installable()) {
			return UpdateOutcome.UNSUPPORTED_PLATFORM;
		}

		Path folder;

		try {
			folder = Files.createDirectories(workspaceManager.resolve(WorkspaceFolders.TEMP));
		} catch (IOException exception) {
			log.warn("Could not prepare the folder for the installer", exception);

			return UpdateOutcome.DOWNLOAD_FAILED;
		}

		PreparedInstaller prepared = UpdateInstallation.prepare(update.get().release(), folder, releaseDownloader);

		if (prepared.refusal() != null) {
			log.warn("The update to {} was not installed: {}", update.get().published(), prepared.refusal());

			return prepared.refusal();
		}

		return start(prepared.installer(), update.get());
	}

	private UpdateOutcome start(Path installer, AvailableUpdate update) {
		try {
			processRunner.start(installer);
		} catch (IOException exception) {
			log.warn("The verified installer could not be started", exception);

			return UpdateOutcome.COULD_NOT_START;
		}

		log.info("Installing {} over {}; this run is ending so the files can be replaced", update.published(),
				update.installed());

		applicationShutdown.endRun();

		return UpdateOutcome.STARTED;
	}

	/**
	 * Package-private so the test can state both platforms. The MSI is the only
	 * artefact published, so anywhere that cannot run one has nothing to install
	 * even when a newer version exists.
	 */
	boolean installable() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains(WINDOWS);
	}
}