package br.com.jorgemelo.nimbusfilemanager.update.infrastructure.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateCheckService;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateInstallAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateInstallService;
import br.com.jorgemelo.nimbusfilemanager.update.application.constants.UpdateMessages;

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

	private final UpdateCheckService updateCheckService;
	private final UpdateInstallService updateInstallService;
	private final UpdateInstallAsyncRunner asyncRunner;

	@Autowired
	public SettingsUpdateWebController(UpdateCheckService updateCheckService,
			UpdateInstallService updateInstallService, UpdateInstallAsyncRunner asyncRunner) {
		this.updateCheckService = updateCheckService;
		this.updateInstallService = updateInstallService;
		this.asyncRunner = asyncRunner;
	}

	@PostMapping("/app/settings/update/check")
	public String checkForUpdate(RedirectAttributes redirectAttributes) {
		if (updateCheckService.check().isEmpty()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS, message(UpdateMessages.UP_TO_DATE));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS, message(UpdateMessages.FOUND,
				updateCheckService.available().map(found -> found.published()).orElse("")));

		return SharedConstants.REDIRECT_SETTINGS;
	}

	/**
	 * Answers immediately and lets the download run behind it. The installer is
	 * over a hundred megabytes: holding the request until it lands left the
	 * browser on a blank minute, and the answer arrived seconds before the
	 * application closed, which is when it was least useful.
	 */
	@PostMapping("/app/settings/update/install")
	public String installUpdate(RedirectAttributes redirectAttributes) {
		if (!updateInstallService.canInstall()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR,
					message(UpdateMessages.NOTHING_TO_INSTALL));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		if (!asyncRunner.start()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR, message(UpdateMessages.ALREADY_RUNNING));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		asyncRunner.install();

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS, message(UpdateMessages.INSTALL_STARTED));

		return SharedConstants.REDIRECT_SETTINGS;
	}
}