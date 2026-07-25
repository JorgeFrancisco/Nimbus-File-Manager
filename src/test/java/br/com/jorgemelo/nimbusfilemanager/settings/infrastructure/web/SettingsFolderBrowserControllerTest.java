package br.com.jorgemelo.nimbusfilemanager.settings.infrastructure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import br.com.jorgemelo.nimbusfilemanager.settings.application.FolderBrowserService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.FolderBrowserView;

class SettingsFolderBrowserControllerTest {

	private final FolderBrowserService folderBrowserService = mock(FolderBrowserService.class);
	private final SettingsFolderBrowserController controller = new SettingsFolderBrowserController(
			folderBrowserService);

	@Test
	void foldersShouldReturnWhatTheServiceBrowsed() {
		FolderBrowserView view = new FolderBrowserView("C:/media", "C:/", List.of(), false);

		when(folderBrowserService.browse("C:/media")).thenReturn(view);

		Assertions.assertThat(controller.folders("C:/media")).isSameAs(view);
	}

	/**
	 * A rejected path is a bad request, not a 500: the service signals it with
	 * {@link IllegalArgumentException} and the controller is the only place that
	 * translates it into a status.
	 */
	@Test
	void foldersShouldTranslateARejectedPathIntoBadRequest() {
		when(folderBrowserService.browse("??")).thenThrow(new IllegalArgumentException("Invalid path"));

		Assertions.assertThatThrownBy(() -> controller.folders("??")).isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("Invalid path")
				.extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}
}