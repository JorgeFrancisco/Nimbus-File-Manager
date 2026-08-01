package br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildScope;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;

/**
 * Metadata rebuild action of the settings page: the guards that keep it from
 * starting, the background start, how a run continues the previous one, and the
 * choices remembered either way.
 */
class SettingsMetadataWebControllerTest {

	private static final List<MetadataRebuildField> FIELDS = List.of(MetadataRebuildField.SUBCATEGORY,
			MetadataRebuildField.DATE);

	private static final String FOLDER = "D:\\photos";

	private static final LocalDateTime PREVIOUS_RUN = LocalDateTime.of(2026, Month.JULY, 26, 11, 16);

	private final MetadataRebuildAsyncRunner runner = mock(MetadataRebuildAsyncRunner.class);
	private final UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
	private final ExecutionQueryService executionQueryService = mock(ExecutionQueryService.class);
	private final InventoryRunningState inventoryRunningState = new InventoryRunningState(executionQueryService);
	private final Clock clock = Clock.fixed(Instant.parse("2026-07-26T14:00:00Z"), ZoneOffset.UTC);

	private final SettingsMetadataWebController controller = new SettingsMetadataWebController(runner, preferences,
			inventoryRunningState, clock);

	private final TestingAuthenticationToken auth = new TestingAuthenticationToken("admin@x", "pw");

	@Test
	void rejectedWithoutAFolderToRebuild() {
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.rebuildMetadata("  ", FIELDS, false, MetadataRebuildScope.CONTINUE, auth, redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("error");

		verify(runner, never()).start(any());
		verify(runner, never()).rebuild(any());
	}

	@Test
	void rejectedWhileAnInventoryIsRunning() {
		when(executionQueryService.active()).thenReturn(Optional.of(inventoryExecution()));

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.rebuildMetadata(FOLDER, FIELDS, false, MetadataRebuildScope.CONTINUE, auth, redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("error");

		verify(runner, never()).rebuild(any());
	}

	@Test
	void rejectedWhenAnotherRebuildIsAlreadyRunning() {
		when(runner.start(any())).thenReturn(false);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.rebuildMetadata(FOLDER, FIELDS, false, MetadataRebuildScope.CONTINUE, auth, redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("error");

		verify(runner, never()).rebuild(any());
	}

	@Test
	void startsInBackgroundAndRemembersTheChoices() {
		when(runner.start(any())).thenReturn(true);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.rebuildMetadata(FOLDER, FIELDS, false, MetadataRebuildScope.ALL, auth, redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("success");

		verify(runner).rebuild(MetadataRebuildRequest.forFolder(FOLDER, FIELDS, false, null));
		verify(preferences).save("admin@x", MetadataRebuildPreferences.PAGE_KEY,
				MetadataRebuildPreferences.SOURCE_PATH_KEY, FOLDER);
		verify(preferences).save("admin@x", MetadataRebuildPreferences.PAGE_KEY, MetadataRebuildPreferences.FIELDS_KEY,
				"SUBCATEGORY,DATE");
		verify(preferences).save("admin@x", MetadataRebuildPreferences.PAGE_KEY, MetadataRebuildPreferences.DRY_RUN_KEY,
				"false");
		verify(preferences).save("admin@x", MetadataRebuildPreferences.PAGE_KEY, MetadataRebuildPreferences.SCOPE_KEY,
				"ALL");
	}

	/**
	 * Forcing everything means no cutoff at all, so the run walks the folder from
	 * the start instead of skipping what the previous one already did.
	 */
	@Test
	void forcingAllIgnoresWhatThePreviousRunAlreadyRebuilt() {
		when(runner.start(any())).thenReturn(true);
		when(preferences.find("admin@x", MetadataRebuildPreferences.PAGE_KEY))
				.thenReturn(Map.of(MetadataRebuildPreferences.LAST_RUN_KEY, PREVIOUS_RUN.toString()));

		controller.rebuildMetadata(FOLDER, FIELDS, false, MetadataRebuildScope.ALL, auth,
				new RedirectAttributesModelMap());

		verify(runner).rebuild(MetadataRebuildRequest.forFolder(FOLDER, FIELDS, false, null));
	}

	@Test
	void continuingSkipsWhatWasRebuiltSinceThePreviousRunStarted() {
		when(runner.start(any())).thenReturn(true);
		when(preferences.find("admin@x", MetadataRebuildPreferences.PAGE_KEY))
				.thenReturn(Map.of(MetadataRebuildPreferences.LAST_RUN_KEY, PREVIOUS_RUN.toString()));

		controller.rebuildMetadata(FOLDER, FIELDS, false, MetadataRebuildScope.CONTINUE, auth,
				new RedirectAttributesModelMap());

		verify(runner).rebuild(MetadataRebuildRequest.forFolder(FOLDER, FIELDS, false, PREVIOUS_RUN));
	}

	/**
	 * With nothing recorded there is nothing to continue from, so the first run
	 * covers the folder rather than silently skipping everything.
	 */
	@Test
	void continuingCoversTheFolderWhileNoRunHasBeenRecorded() {
		when(runner.start(any())).thenReturn(true);

		controller.rebuildMetadata(FOLDER, FIELDS, false, MetadataRebuildScope.CONTINUE, auth,
				new RedirectAttributesModelMap());

		verify(runner).rebuild(MetadataRebuildRequest.forFolder(FOLDER, FIELDS, false, null));
	}

	/**
	 * The mark is what the next run continues from, so it moves only when a real
	 * run starts - never on a rejected one, and never on a simulation, which writes
	 * nothing.
	 */
	@Test
	void stampsTheRunOnlyWhenItReallyStartsAndIsNotASimulation() {
		when(runner.start(any())).thenReturn(true);

		controller.rebuildMetadata(FOLDER, FIELDS, false, MetadataRebuildScope.CONTINUE, auth,
				new RedirectAttributesModelMap());

		controller.rebuildMetadata(FOLDER, FIELDS, true, MetadataRebuildScope.CONTINUE, auth,
				new RedirectAttributesModelMap());
		controller.rebuildMetadata("  ", FIELDS, false, MetadataRebuildScope.CONTINUE, auth,
				new RedirectAttributesModelMap());

		// Three posts, one mark: the simulation and the rejected one left it alone.
		verify(preferences, times(1)).save("admin@x", MetadataRebuildPreferences.PAGE_KEY,
				MetadataRebuildPreferences.LAST_RUN_KEY, LocalDateTime.of(2026, Month.JULY, 26, 14, 0).toString());
	}

	@Test
	void asksForTheWholeFolderRatherThanTheDefaultSample() {
		Assertions.assertThat(MetadataRebuildRequest.forFolder(FOLDER, FIELDS, false, null).safeLimit())
				.isEqualTo(MetadataRebuildRequest.MAX_LIMIT);
	}

	/**
	 * The form must reopen on what was asked for even when the rebuild could not
	 * start, so the admin does not have to fill it in again to retry.
	 */
	@Test
	void remembersTheChoicesEvenWhenTheRebuildCannotStart() {
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.rebuildMetadata(null, null, true, MetadataRebuildScope.CONTINUE, auth, redirect);

		verify(preferences).save("admin@x", MetadataRebuildPreferences.PAGE_KEY,
				MetadataRebuildPreferences.SOURCE_PATH_KEY, "");
		verify(preferences).save("admin@x", MetadataRebuildPreferences.PAGE_KEY, MetadataRebuildPreferences.FIELDS_KEY,
				"");
		verify(preferences).save("admin@x", MetadataRebuildPreferences.PAGE_KEY, MetadataRebuildPreferences.DRY_RUN_KEY,
				"true");
	}

	private static ExecutionResponse inventoryExecution() {
		return new ExecutionResponse(1L, "INVENTORY", "PROCESSING_FILES", LocalDateTime.now(), null, "src", null, 1, 1,
				0, 0, 0, 0, null, null, "running", false);
	}
}