package br.com.jorgemelo.nimbusfilemanager.media.infrastructure.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.media.application.dto.ExplorerActionResult;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.ExplorerItemProperties;
import br.com.jorgemelo.nimbusfilemanager.media.application.explorer.ExplorerDeletionService;
import br.com.jorgemelo.nimbusfilemanager.media.application.explorer.ExplorerPropertiesService;
import br.com.jorgemelo.nimbusfilemanager.media.application.explorer.ExplorerRenameService;
import br.com.jorgemelo.nimbusfilemanager.media.domain.enums.ExplorerDeleteMode;

/**
 * The card menu talks to these three handlers, and the only decision they own is
 * which service answers a delete: the mode picked in the dialog is what
 * separates a recoverable removal from an irreversible one, so it is worth
 * pinning that the destructive path is never taken by default.
 */
class FileExplorerActionsControllerTest {

	private final ExplorerPropertiesService propertiesService = mock(ExplorerPropertiesService.class);
	private final ExplorerDeletionService deletionService = mock(ExplorerDeletionService.class);
	private final ExplorerRenameService renameService = mock(ExplorerRenameService.class);

	private final FileExplorerActionsController controller = new FileExplorerActionsController(propertiesService,
			deletionService, renameService);

	@Test
	void returnsThePropertiesOfTheRequestedPath() throws IOException {
		ExplorerItemProperties properties = new ExplorerItemProperties("photo.jpg", "D:\\photos\\photo.jpg",
				"D:\\photos", false, 2048, "2.00 KB", null, null, "JPG", "-", "-", true, "Inventariado");

		when(propertiesService.of(any(Path.class))).thenReturn(properties);

		Assertions.assertThat(controller.properties("D:\\photos\\photo.jpg").getBody()).isSameAs(properties);
	}

	@Test
	void quarantinesWhenThatIsTheChosenMode() {
		ExplorerActionResult result = ExplorerActionResult.of("ok");

		when(deletionService.quarantine(any())).thenReturn(result);

		Assertions.assertThat(controller.delete("D:\\photos\\photo.jpg", ExplorerDeleteMode.QUARANTINE))
				.isSameAs(result);

		verify(deletionService, never()).deletePermanently(any());
	}

	@Test
	void deletesForGoodOnlyWhenThePermanentModeIsAskedFor() {
		ExplorerActionResult result = ExplorerActionResult.of("ok");

		when(deletionService.deletePermanently(any())).thenReturn(result);

		Assertions.assertThat(controller.delete("D:\\photos\\photo.jpg", ExplorerDeleteMode.PERMANENT))
				.isSameAs(result);

		verify(deletionService, never()).quarantine(any());
	}

	@Test
	void passesTheNewNameToTheRenameService() {
		ExplorerActionResult result = ExplorerActionResult.of("ok");

		when(renameService.rename(any(), any())).thenReturn(result);

		Assertions.assertThat(controller.rename("D:\\photos\\photo.jpg", "holiday.jpg")).isSameAs(result);

		verify(renameService).rename(any(Path.class), any());
	}
}