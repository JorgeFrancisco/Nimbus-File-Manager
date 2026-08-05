package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateExclusionService;

class SettingsDuplicateExclusionWebControllerTest {

	private final DuplicateExclusionService duplicateExclusions = mock(DuplicateExclusionService.class);
	private final SettingsDuplicateExclusionWebController controller = new SettingsDuplicateExclusionWebController(
			duplicateExclusions);

	@Test
	void removeDuplicateFileExclusionDeletesIt() {
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		Assertions.assertThat(controller.removeDuplicateFileExclusion(5L, redirect))
				.isEqualTo("redirect:/app/settings");

		verify(duplicateExclusions).removeFileExclusion(5L);
		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("success");
	}

	@Test
	void removeDuplicateFolderExclusionDeletesIt() {
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		Assertions.assertThat(controller.removeDuplicateFolderExclusion(8L, redirect))
				.isEqualTo("redirect:/app/settings");

		verify(duplicateExclusions).removeFolderExclusion(8L);
		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("success");
	}
}