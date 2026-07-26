package br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MetadataRebuildAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataRebuildPreferences;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;

/**
 * Metadata rebuild action of the settings page: the guards that keep it from
 * starting, the background start, and the choices remembered either way.
 */
class SettingsMetadataWebControllerTest {

	private final MetadataRebuildAsyncRunner runner = mock(MetadataRebuildAsyncRunner.class);
	private final UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
	private final ExecutionQueryService executionQueryService = mock(ExecutionQueryService.class);
	private final InventoryRunningState inventoryRunningState = new InventoryRunningState(executionQueryService);

	private final SettingsMetadataWebController controller = new SettingsMetadataWebController(runner, preferences,
			inventoryRunningState);

	private final TestingAuthenticationToken auth = new TestingAuthenticationToken("admin@x", "pw");

	@Test
	void rejectedWithoutAFolderToRebuild() {
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.rebuildMetadata(request("  "), auth, redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("error");

		verify(runner, never()).start(any());
		verify(runner, never()).rebuild(any());
	}

	@Test
	void rejectedWhileAnInventoryIsRunning() {
		when(executionQueryService.active()).thenReturn(Optional.of(inventoryExecution()));

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.rebuildMetadata(request("D:\\photos"), auth, redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("error");

		verify(runner, never()).rebuild(any());
	}

	@Test
	void rejectedWhenAnotherRebuildIsAlreadyRunning() {
		when(runner.start(any())).thenReturn(false);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.rebuildMetadata(request("D:\\photos"), auth, redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("error");

		verify(runner, never()).rebuild(any());
	}

	@Test
	void startsInBackgroundAndRemembersTheChoices() {
		when(runner.start(any())).thenReturn(true);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		MetadataRebuildRequest request = request("D:\\photos");

		controller.rebuildMetadata(request, auth, redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("success");

		verify(runner).rebuild(request);
		verify(preferences).save("admin@x", MetadataRebuildPreferences.PAGE_KEY,
				MetadataRebuildPreferences.SOURCE_PATH_KEY, "D:\\photos");
		verify(preferences).save("admin@x", MetadataRebuildPreferences.PAGE_KEY,
				MetadataRebuildPreferences.FIELDS_KEY, "SUBCATEGORY,DATE");
		verify(preferences).save("admin@x", MetadataRebuildPreferences.PAGE_KEY,
				MetadataRebuildPreferences.DRY_RUN_KEY, "false");
	}

	/**
	 * The form must reopen on what was asked for even when the rebuild could not
	 * start, so the admin does not have to fill it in again to retry.
	 */
	@Test
	void remembersTheChoicesEvenWhenTheRebuildCannotStart() {
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.rebuildMetadata(new MetadataRebuildRequest(null, null, null, null, null, true), auth, redirect);

		verify(preferences).save("admin@x", MetadataRebuildPreferences.PAGE_KEY,
				MetadataRebuildPreferences.SOURCE_PATH_KEY, "");
		verify(preferences).save("admin@x", MetadataRebuildPreferences.PAGE_KEY,
				MetadataRebuildPreferences.FIELDS_KEY, "");
		verify(preferences).save("admin@x", MetadataRebuildPreferences.PAGE_KEY,
				MetadataRebuildPreferences.DRY_RUN_KEY, "true");
	}

	private static ExecutionResponse inventoryExecution() {
		return new ExecutionResponse(1L, "INVENTORY", "PROCESSING_FILES", LocalDateTime.now(), null, "src", null, 1, 1,
				0, 0, 0, 0, null, null, "running", false);
	}

	private MetadataRebuildRequest request(String sourcePath) {
		return new MetadataRebuildRequest(sourcePath,
				List.of(MetadataRebuildField.SUBCATEGORY, MetadataRebuildField.DATE), null, null, null, false);
	}
}