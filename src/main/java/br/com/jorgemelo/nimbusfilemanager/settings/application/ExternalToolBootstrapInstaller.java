package br.com.jorgemelo.nimbusfilemanager.settings.application;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.ExternalToolStatus;
import lombok.extern.slf4j.Slf4j;

/**
 * Installs the external tools on any start that finds this installation without
 * its own copy, so a fresh installation converts video and builds thumbnails
 * without anyone being asked to fetch ffmpeg. Whoever runs this application has
 * no reason to know what ffmpeg is, and a question they cannot answer is not a
 * choice - it is a dead end.
 *
 * <p>
 * A binary already on PATH does not stop the download. It used to, and the
 * result was an installation running whatever build happened to be on the
 * machine - unknown provenance, possibly years old, missing codecs this
 * application asks for. PATH is the fallback that keeps the features working
 * while the download has not succeeded, so every start retries until the copy
 * is here.
 */
@Slf4j
@Component
public class ExternalToolBootstrapInstaller {

	private final ExternalToolInstaller installer;
	private final ExternalToolInstallAsyncRunner installAsyncRunner;

	public ExternalToolBootstrapInstaller(ExternalToolInstaller installer,
			ExternalToolInstallAsyncRunner installAsyncRunner) {
		this.installer = installer;
		this.installAsyncRunner = installAsyncRunner;
	}

	/**
	 * Runs after the context is up - a ~70 MB download must never sit between the
	 * operator and the login page.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void installWhenMissing() {
		ExternalToolStatus status = installer.status();

		if (!status.installable()) {
			return;
		}

		// Only the empty state installs itself. With our own binary in place there
		// is a file to overwrite, and the startup inventory may be executing it -
		// on Windows that fails outright. Updating an installed copy is left to the
		// settings button, which waits for an idle inventory.
		if (status.bundled()) {
			return;
		}

		if (!installAsyncRunner.start()) {
			return;
		}

		log.info("FFmpeg/FFprobe not installed here: downloading them into {}", status.directory());

		installAsyncRunner.install();
	}
}