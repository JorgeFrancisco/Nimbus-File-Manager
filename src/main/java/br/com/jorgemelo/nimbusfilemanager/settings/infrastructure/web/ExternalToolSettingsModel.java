package br.com.jorgemelo.nimbusfilemanager.settings.infrastructure.web;

import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.ToolInstallSnapshot;
import br.com.jorgemelo.nimbusfilemanager.execution.application.EtaLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolInstallAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolInstaller;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web.SettingsSectionModel;

/**
 * Read-side assembler for the external-tools section of the settings page: what
 * is installed, where it came from and how the running installation is doing.
 * Extracted from the settings controller so its render handler keeps a small
 * constructor; the matching action lives in {@link SettingsToolsWebController}.
 */
@Component
@Profile(NimbusProfiles.APP)
public class ExternalToolSettingsModel implements SettingsSectionModel {

	private final ExternalToolInstaller externalToolInstaller;
	private final ExternalToolInstallAsyncRunner installAsyncRunner;
	private final EtaLabels etaLabels;

	@Autowired
	public ExternalToolSettingsModel(ExternalToolInstaller externalToolInstaller,
			ExternalToolInstallAsyncRunner installAsyncRunner,
			EtaLabels etaLabels) {
		this.externalToolInstaller = externalToolInstaller;
		this.installAsyncRunner = installAsyncRunner;
		this.etaLabels = etaLabels;
	}

	@Override
	public void addTo(Model model, Authentication authentication) {
		model.addAttribute("toolStatus", externalToolInstaller.status());
		model.addAttribute("toolInstallRunning", installAsyncRunner.isRunning());
		model.addAttribute("toolInstallError", installAsyncRunner.lastError());
		model.addAttribute("toolInstallResult", installAsyncRunner.lastResult());
		// Read once: it is live progress, so two reads are two different moments -
		// and the sentence would then describe a moment the numbers beside it do not.
		ToolInstallSnapshot progress = installAsyncRunner.progress();

		model.addAttribute("toolInstallProgress", progress);
		model.addAttribute("toolInstallEta", etaLabels.label(progress.eta()));
	}
}