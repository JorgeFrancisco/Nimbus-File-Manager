package br.com.jorgemelo.nimbusfilemanager.update.infrastructure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateCheckService;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateInstallAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateInstallService;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.AvailableUpdate;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.PublishedRelease;

/**
 * The screen has to answer immediately and let the download run behind it. It
 * used to wait for the whole installer - about a minute of blank page - and the
 * answer then arrived seconds before the application closed, which is when it
 * was least useful.
 */
class SettingsUpdateWebControllerTest {

	private final UpdateCheckService updateCheckService = mock(UpdateCheckService.class);
	private final UpdateInstallService updateInstallService = mock(UpdateInstallService.class);
	private final UpdateInstallAsyncRunner asyncRunner = mock(UpdateInstallAsyncRunner.class);

	private final SettingsUpdateWebController controller = new SettingsUpdateWebController(updateCheckService,
			updateInstallService, asyncRunner);

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

	/**
	 * The request returns while the download is still running, so what comes back
	 * says the work started - not that it finished.
	 */
	@Test
	void startsTheDownloadInTheBackgroundAndAnswersAtOnce() {
		when(updateInstallService.canInstall()).thenReturn(true);
		when(asyncRunner.start()).thenReturn(true);

		RedirectAttributes attributes = new RedirectAttributesModelMap();

		String view = controller.installUpdate(attributes);

		Assertions.assertThat(view).isEqualTo(SharedConstants.REDIRECT_SETTINGS);
		Assertions.assertThat(attributes.getFlashAttributes()).containsKey(SharedConstants.ATTR_SUCCESS);

		verify(asyncRunner).install();
	}

	/**
	 * A second click while the first download is running would fetch the same file
	 * into the same folder, and the loser would verify bytes the winner was still
	 * writing.
	 */
	@Test
	void refusesASecondInstallWhileOneIsRunning() {
		when(updateInstallService.canInstall()).thenReturn(true);
		when(asyncRunner.start()).thenReturn(false);

		RedirectAttributes attributes = new RedirectAttributesModelMap();

		controller.installUpdate(attributes);

		Assertions.assertThat(attributes.getFlashAttributes()).containsKey(SharedConstants.ATTR_ERROR);

		verify(asyncRunner, never()).install();
	}

	@Test
	void refusesToInstallWhenThereIsNothingToInstall() {
		when(updateInstallService.canInstall()).thenReturn(false);

		RedirectAttributes attributes = new RedirectAttributesModelMap();

		controller.installUpdate(attributes);

		Assertions.assertThat(attributes.getFlashAttributes()).containsKey(SharedConstants.ATTR_ERROR);

		verify(asyncRunner, never()).start();
		verify(asyncRunner, never()).install();
	}

	private static AvailableUpdate update() {
		return new AvailableUpdate("6.0.0.147", "v6.1.0.160", new PublishedRelease("v6.1.0.160", "page", "a.msi",
				"https://example.invalid/a.msi", "https://example.invalid/a.msi.sha256", 10L));
	}
}