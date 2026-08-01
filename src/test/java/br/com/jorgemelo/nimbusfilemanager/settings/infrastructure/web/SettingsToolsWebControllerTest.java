package br.com.jorgemelo.nimbusfilemanager.settings.infrastructure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolInstallAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;

/**
 * Guards of the tool installation: an inventory in course, an installation
 * already running, and the background start when neither blocks it.
 */
class SettingsToolsWebControllerTest {

	private final ExternalToolInstallAsyncRunner installAsyncRunner = mock(ExternalToolInstallAsyncRunner.class);
	private final InventoryRunningState inventoryRunningState = mock(InventoryRunningState.class);

	private final SettingsToolsWebController controller = new SettingsToolsWebController(installAsyncRunner,
			inventoryRunningState);

	@Test
	void startsTheInstallationWhenNothingBlocksIt() {
		when(installAsyncRunner.start()).thenReturn(true);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		String view = controller.installTools(redirect);

		verify(installAsyncRunner).install();

		Assertions.assertThat(view).isEqualTo(SharedConstants.REDIRECT_SETTINGS);
		Assertions.assertThat(redirect.getFlashAttributes()).containsKey(SharedConstants.ATTR_SUCCESS);
	}

	/**
	 * Replacing a running executable fails on Windows, so an inventory - the
	 * heaviest ffmpeg user - stops the install with a reason on screen instead of
	 * a half-written folder.
	 */
	@Test
	void refusesWhileAnInventoryIsRunning() {
		when(inventoryRunningState.isRunning()).thenReturn(true);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		String view = controller.installTools(redirect);

		verify(installAsyncRunner, never()).install();

		Assertions.assertThat(view).isEqualTo(SharedConstants.REDIRECT_SETTINGS);
		Assertions.assertThat(redirect.getFlashAttributes()).containsKey(SharedConstants.ATTR_ERROR);
	}

	@Test
	void refusesWhenAnInstallationIsAlreadyRunning() {
		when(installAsyncRunner.start()).thenReturn(false);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.installTools(redirect);

		verify(installAsyncRunner, never()).install();

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey(SharedConstants.ATTR_ERROR);
	}
}