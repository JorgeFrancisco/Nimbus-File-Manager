package br.com.jorgemelo.nimbusfilemanager.database.infrastructure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import br.com.jorgemelo.nimbusfilemanager.database.application.EmbeddedDatabaseAdminService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;

/**
 * The button that fetches the database. What it has to get right is not the
 * download - that is the installer's job - but what the screen is told
 * afterwards: an install that could not replace a running server has to say so,
 * because reporting plain success would describe something that has not
 * happened yet.
 */
class SettingsDatabaseWebControllerTest {

	private final EmbeddedDatabaseAdminService adminService = mock(EmbeddedDatabaseAdminService.class);

	@Test
	void reportsAFailedInstallWithItsOwnMessage() {
		when(adminService.install()).thenReturn(false);

		RedirectAttributes attributes = new RedirectAttributesModelMap();

		Assertions.assertThat(controller().installDatabase(attributes)).isEqualTo(SharedConstants.REDIRECT_SETTINGS);

		Assertions.assertThat(attributes.getFlashAttributes()).containsKey(SharedConstants.ATTR_ERROR)
				.doesNotContainKey(SharedConstants.ATTR_SUCCESS);
	}

	/**
	 * With no cluster of its own serving this run there is nothing in use to
	 * replace, so what was installed is what will be used - no warning needed.
	 */
	@Test
	void reportsPlainSuccessWhenNoServerWasInUse() {
		when(adminService.install()).thenReturn(true);

		RedirectAttributes attributes = new RedirectAttributesModelMap();

		controller().installDatabase(attributes);

		Assertions.assertThat(attributes.getFlashAttributes()).containsKey(SharedConstants.ATTR_SUCCESS)
				.doesNotContainKey(SharedConstants.ATTR_ERROR);
	}

	private SettingsDatabaseWebController controller() {
		return new SettingsDatabaseWebController(adminService);
	}
}