package br.com.jorgemelo.nimbusfilemanager.settings.infrastructure.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolInstallAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolInstaller;

/**
 * Read-side assembler for the external-tools section of the settings page: what
 * is installed, where it came from and how the running installation is doing.
 * Extracted from the settings controller so its render handler keeps a small
 * constructor; the matching action lives in {@link SettingsToolsWebController}.
 */
@Component
public class ExternalToolSettingsModel {

	private final ExternalToolInstaller externalToolInstaller;
	private final ExternalToolInstallAsyncRunner installAsyncRunner;

	@Autowired
	public ExternalToolSettingsModel(ExternalToolInstaller externalToolInstaller,
			ExternalToolInstallAsyncRunner installAsyncRunner) {
		this.externalToolInstaller = externalToolInstaller;
		this.installAsyncRunner = installAsyncRunner;
	}

	public void addTo(Model model) {
		model.addAttribute("toolStatus", externalToolInstaller.status());
		model.addAttribute("toolInstallRunning", installAsyncRunner.isRunning());
		model.addAttribute("toolInstallError", installAsyncRunner.lastError());
		model.addAttribute("toolInstallResult", installAsyncRunner.lastResult());
		model.addAttribute("toolInstallProgress", installAsyncRunner.progress());
	}
}