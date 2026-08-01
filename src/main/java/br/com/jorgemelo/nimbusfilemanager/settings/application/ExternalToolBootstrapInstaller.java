package br.com.jorgemelo.nimbusfilemanager.settings.application;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.ExternalToolStatus;
import lombok.extern.slf4j.Slf4j;

/**
 * Installs the external tools on the first start that finds them missing, so a
 * fresh installation converts video and builds thumbnails without anyone being
 * asked to fetch ffmpeg. Whoever runs this application has no reason to know
 * what ffmpeg is, and a question they cannot answer is not a choice - it is a
 * dead end.
 *
 * <p>
 * It stays quiet whenever the answer is already known: when the tools run, when
 * the platform installs them through a package manager, and when the operator
 * turned the setting off. The manual install/update button on the settings
 * screen remains the way to force it.
 */
@Slf4j
@Component
public class ExternalToolBootstrapInstaller {

	private final ExternalToolInstaller installer;
	private final ExternalToolInstallAsyncRunner installAsyncRunner;
	private final AppSettingService appSettingService;

	public ExternalToolBootstrapInstaller(ExternalToolInstaller installer,
			ExternalToolInstallAsyncRunner installAsyncRunner, AppSettingService appSettingService) {
		this.installer = installer;
		this.installAsyncRunner = installAsyncRunner;
		this.appSettingService = appSettingService;
	}

	/**
	 * Runs after the context is up - the settings have been seeded by then, and a
	 * ~70 MB download must never sit between the operator and the login page.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void installWhenMissing() {
		if (!appSettingService.booleanValue(SettingsConstants.TOOL_AUTO_INSTALL, true)) {
			return;
		}

		ExternalToolStatus status = installer.status();

		if (!status.installable()) {
			return;
		}

		// Only the empty state installs itself. With a tool already in place there is
		// a file to overwrite, and the startup inventory may be executing it - on
		// Windows that fails outright. Completing a half-installed folder is left to
		// the settings button, which waits for an idle inventory.
		if (status.ffmpegAvailable() || status.ffprobeAvailable()) {
			return;
		}

		if (!installAsyncRunner.start()) {
			return;
		}

		log.info("FFmpeg/FFprobe not found: downloading them into {}", status.directory());

		installAsyncRunner.install();
	}
}