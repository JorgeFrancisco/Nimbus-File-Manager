package br.com.jorgemelo.nimbusfilemanager.update.infrastructure.web;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import br.com.jorgemelo.nimbusfilemanager.shared.application.InstalledVersion;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.UpdateProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web.SettingsSectionModel;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateCheckService;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateInstallProgress;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateInstallService;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.AvailableUpdate;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.UpdateStatus;

/**
 * Read-side assembler for the update section of the settings page, split from
 * its actions the same way the embedded-database section is.
 *
 * <p>
 * Checking is offered only to a run that knows its own version. Outside a
 * packaged build there is nothing to compare against, and a button that could
 * only ever answer "nothing found" would be a button that lies about why.
 */
@Component
public class UpdateSettingsModel implements SettingsSectionModel {

	private final UpdateCheckService updateCheckService;
	private final UpdateInstallService updateInstallService;
	private final UpdateProperties updateProperties;
	private final UpdateInstallProgress progress;

	@Autowired
	public UpdateSettingsModel(UpdateCheckService updateCheckService, UpdateInstallService updateInstallService,
			UpdateProperties updateProperties, UpdateInstallProgress progress) {
		this.updateCheckService = updateCheckService;
		this.updateInstallService = updateInstallService;
		this.updateProperties = updateProperties;
		this.progress = progress;
	}

	@Override
	public void addTo(Model model, Authentication authentication) {
		Optional<String> installed = InstalledVersion.current();
		Optional<AvailableUpdate> update = updateCheckService.available();

		model.addAttribute("updateStatus",
				new UpdateStatus(installed.orElse(null), update.isPresent(),
						update.map(AvailableUpdate::published).orElse(null),
						update.map(found -> found.release().page()).orElse(null),
						updateProperties.enabled() && installed.isPresent(), updateInstallService.canInstall()));

		// The install outlives the request that started it, so the screen reads its
		// state here rather than from a flash attribute that only existed once.
		model.addAttribute("updateProgress", progress.snapshot());
	}
}