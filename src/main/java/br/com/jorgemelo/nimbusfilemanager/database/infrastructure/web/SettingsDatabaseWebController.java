package br.com.jorgemelo.nimbusfilemanager.database.infrastructure.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.com.jorgemelo.nimbusfilemanager.database.application.EmbeddedDatabaseAdminService;
import br.com.jorgemelo.nimbusfilemanager.database.infrastructure.config.EmbeddedDatabaseBootstrap;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * Installs or updates the embedded PostgreSQL from the Sistema tab.
 *
 * <p>
 * Unlike the ffmpeg action this one is synchronous. It exists for the run that
 * did not install the server automatically, which is a run with no cluster of
 * its own - so there is nothing running to keep busy, and the operator gets the
 * outcome on the same page rather than a progress bar to watch.
 *
 * <p>
 * When the embedded cluster <em>is</em> serving this run, the message says the
 * update only applies on the next start: Windows will not let the running
 * {@code postgres.exe} be overwritten, and a screen that reported plain success
 * would be describing something that did not happen yet.
 */
@Controller
public class SettingsDatabaseWebController extends LocalizedComponent {

	private final EmbeddedDatabaseAdminService adminService;

	@Autowired
	public SettingsDatabaseWebController(EmbeddedDatabaseAdminService adminService) {
		this.adminService = adminService;
	}

	@PostMapping("/app/settings/database/install")
	public String installDatabase(RedirectAttributes redirectAttributes) {
		boolean serving = EmbeddedDatabaseBootstrap.serving();

		if (!adminService.install()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR,
					message("backend.settings.databaseInstallFailed"));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS, message(
				serving ? "backend.settings.databaseInstalledRestartRequired" : "backend.settings.databaseInstalled"));

		return SharedConstants.REDIRECT_SETTINGS;
	}
}