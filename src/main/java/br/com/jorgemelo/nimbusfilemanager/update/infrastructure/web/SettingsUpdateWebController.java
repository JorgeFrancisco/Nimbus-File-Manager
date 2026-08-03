package br.com.jorgemelo.nimbusfilemanager.update.infrastructure.web;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateCheckService;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateInstallService;
import br.com.jorgemelo.nimbusfilemanager.update.domain.enums.UpdateOutcome;

/**
 * Checking for an update and installing one, from the Sistema tab.
 *
 * <p>
 * Every refusal is answered with its reason rather than with a silent return to
 * the page. An update that did not happen looks exactly like one that did if
 * the screen says nothing, and the reasons here are ones the person can act
 * on - being offline, being on a platform this installer does not target, or a
 * download whose bytes did not match what was published.
 *
 * <p>
 * The success path says the application is closing, because it is: installing
 * means replacing the files this run is executing from. Saying so beats a
 * window that simply disappears.
 */
@Controller
public class SettingsUpdateWebController extends LocalizedComponent {

	private static final Map<UpdateOutcome, String> MESSAGES = Map.of(UpdateOutcome.NOTHING_TO_INSTALL,
			"backend.settings.updateNothingToInstall", UpdateOutcome.UNSUPPORTED_PLATFORM,
			"backend.settings.updateUnsupportedPlatform", UpdateOutcome.DOWNLOAD_FAILED,
			"backend.settings.updateDownloadFailed", UpdateOutcome.CHECKSUM_UNAVAILABLE,
			"backend.settings.updateChecksumUnavailable", UpdateOutcome.CHECKSUM_MISMATCH,
			"backend.settings.updateChecksumMismatch", UpdateOutcome.COULD_NOT_START,
			"backend.settings.updateCouldNotStart");

	private final UpdateCheckService updateCheckService;
	private final UpdateInstallService updateInstallService;

	@Autowired
	public SettingsUpdateWebController(UpdateCheckService updateCheckService,
			UpdateInstallService updateInstallService) {
		this.updateCheckService = updateCheckService;
		this.updateInstallService = updateInstallService;
	}

	@PostMapping("/app/settings/update/check")
	public String checkForUpdate(RedirectAttributes redirectAttributes) {
		if (updateCheckService.check().isEmpty()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS,
					message("backend.settings.updateUpToDate"));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS, message("backend.settings.updateFound",
				updateCheckService.available().map(found -> found.published()).orElse("")));

		return SharedConstants.REDIRECT_SETTINGS;
	}

	@PostMapping("/app/settings/update/install")
	public String installUpdate(RedirectAttributes redirectAttributes) {
		UpdateOutcome outcome = updateInstallService.install();

		if (outcome == UpdateOutcome.STARTED) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS,
					message("backend.settings.updateStarted"));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR, message(MESSAGES.get(outcome)));

		return SharedConstants.REDIRECT_SETTINGS;
	}
}