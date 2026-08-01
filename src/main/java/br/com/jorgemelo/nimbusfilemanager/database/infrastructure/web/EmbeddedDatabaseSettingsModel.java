package br.com.jorgemelo.nimbusfilemanager.database.infrastructure.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import br.com.jorgemelo.nimbusfilemanager.database.application.EmbeddedDatabaseAdminService;
import br.com.jorgemelo.nimbusfilemanager.database.infrastructure.config.EmbeddedDatabaseBootstrap;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web.SettingsSectionModel;

/**
 * Read-side assembler for the embedded-database section of the settings page.
 * The matching action lives in {@link SettingsDatabaseWebController}, the same
 * way the external-tools section is split.
 */
@Component
public class EmbeddedDatabaseSettingsModel implements SettingsSectionModel {

	private final EmbeddedDatabaseAdminService adminService;

	@Autowired
	public EmbeddedDatabaseSettingsModel(EmbeddedDatabaseAdminService adminService) {
		this.adminService = adminService;
	}

	@Override
	public void addTo(Model model, Authentication authentication) {
		model.addAttribute("embeddedDatabaseStatus", adminService.status(EmbeddedDatabaseBootstrap.serving()));
	}
}