package br.com.jorgemelo.nimbusfilemanager.settings.infrastructure.web;

import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolInstallAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * Installs ffmpeg/ffprobe from the Sistema tab. The action waits for an idle
 * inventory because that is what keeps ffmpeg busy: on Windows a running
 * executable cannot be replaced, so starting mid-inventory would fail halfway
 * and leave the folder with half of a new build. The read side of this same
 * section lives in {@link ExternalToolSettingsModel}.
 */
@Controller
@Profile(NimbusProfiles.APP)
public class SettingsToolsWebController extends LocalizedComponent {

	private final ExternalToolInstallAsyncRunner installAsyncRunner;
	private final InventoryRunningState inventoryRunningState;

	@Autowired
	public SettingsToolsWebController(ExternalToolInstallAsyncRunner installAsyncRunner,
			InventoryRunningState inventoryRunningState) {
		this.installAsyncRunner = installAsyncRunner;
		this.inventoryRunningState = inventoryRunningState;
	}

	@PostMapping("/app/settings/tools/install")
	public String installTools(RedirectAttributes redirectAttributes) {
		if (inventoryRunningState.isRunning()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR,
					message("backend.settings.toolsInventoryBlocked"));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		if (!installAsyncRunner.start()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR,
					message("backend.settings.toolsInstallRunning"));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		installAsyncRunner.install();

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS,
				message("backend.settings.toolsInstallStarted"));

		return SharedConstants.REDIRECT_SETTINGS;
	}
}