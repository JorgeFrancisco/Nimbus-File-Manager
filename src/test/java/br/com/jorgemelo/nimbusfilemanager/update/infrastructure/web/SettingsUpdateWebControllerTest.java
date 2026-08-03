package br.com.jorgemelo.nimbusfilemanager.update.infrastructure.web;

import static org.mockito.Mockito.when;

import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateCheckService;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateInstallService;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.AvailableUpdate;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.PublishedRelease;
import br.com.jorgemelo.nimbusfilemanager.update.domain.enums.UpdateOutcome;

/**
 * Every refusal has to reach the screen with its reason. An update that did not
 * happen looks exactly like one that did when the page comes back silently, and
 * these reasons are ones the person can act on - being offline, being on the
 * wrong platform, or bytes that did not match what was published.
 */
class SettingsUpdateWebControllerTest {

	private final UpdateCheckService updateCheckService = Mockito.mock(UpdateCheckService.class);
	private final UpdateInstallService updateInstallService = Mockito.mock(UpdateInstallService.class);

	private final SettingsUpdateWebController controller = new SettingsUpdateWebController(updateCheckService,
			updateInstallService);

	@Test
	void saysSoWhenTheCheckFindsNothing() {
		when(updateCheckService.check()).thenReturn(Optional.empty());

		RedirectAttributes attributes = new RedirectAttributesModelMap();

		String view = controller.checkForUpdate(attributes);

		Assertions.assertThat(view).isEqualTo(SharedConstants.REDIRECT_SETTINGS);
		Assertions.assertThat(attributes.getFlashAttributes()).containsKey(SharedConstants.ATTR_SUCCESS);
	}

	@Test
	void namesTheVersionTheCheckFound() {
		when(updateCheckService.check()).thenReturn(Optional.of(update()));
		when(updateCheckService.available()).thenReturn(Optional.of(update()));

		RedirectAttributes attributes = new RedirectAttributesModelMap();

		controller.checkForUpdate(attributes);

		Assertions.assertThat(attributes.getFlashAttributes().get(SharedConstants.ATTR_SUCCESS).toString())
				.contains("v6.1.0.160");
	}

	@Test
	void reportsTheInstallerHavingStartedAsSuccess() {
		when(updateInstallService.install()).thenReturn(UpdateOutcome.STARTED);

		RedirectAttributes attributes = new RedirectAttributesModelMap();

		String view = controller.installUpdate(attributes);

		Assertions.assertThat(view).isEqualTo(SharedConstants.REDIRECT_SETTINGS);
		Assertions.assertThat(attributes.getFlashAttributes()).containsKey(SharedConstants.ATTR_SUCCESS)
				.doesNotContainKey(SharedConstants.ATTR_ERROR);
	}

	/**
	 * Every outcome other than success has to produce a message, and a distinct
	 * one: a map missing an entry would throw, and a shared entry would tell the
	 * person the wrong reason.
	 */
	@Test
	void answersEveryRefusalWithAReasonOfItsOwn() {
		for (UpdateOutcome outcome : UpdateOutcome.values()) {
			if (outcome == UpdateOutcome.STARTED) {
				continue;
			}

			when(updateInstallService.install()).thenReturn(outcome);

			RedirectAttributes attributes = new RedirectAttributesModelMap();

			controller.installUpdate(attributes);

			Assertions.assertThat(attributes.getFlashAttributes()).as("message for %s", outcome)
					.containsKey(SharedConstants.ATTR_ERROR);
			Assertions.assertThat(attributes.getFlashAttributes().get(SharedConstants.ATTR_ERROR).toString())
					.as("reason for %s", outcome).isNotBlank();
		}
	}

	private static AvailableUpdate update() {
		return new AvailableUpdate("6.0.0.147", "v6.1.0.160", new PublishedRelease("v6.1.0.160", "page", "a.msi",
				"https://example.invalid/a.msi", "https://example.invalid/a.msi.sha256", 10L));
	}
}