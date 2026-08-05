package br.com.jorgemelo.nimbusfilemanager.settings.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.LibrarySwitchPayload;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * The switch, as an intention that outlives the process that asked for it.
 *
 * <p>
 * The pair of tests that matter most are about identity: what a queued switch
 * says it will forget, and what the worker actually forgets when it claims it.
 * Between the two there can be another switch, a restart, or both.
 */
class LibrarySwitchQueueTest {

	private final ExecutionEnqueueService executionEnqueueService = mock(ExecutionEnqueueService.class);
	private final ExecutionCancellationService executionCancellationService = mock(ExecutionCancellationService.class);
	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());

	private final LibraryCatalogCleanupService libraryCatalogCleanupService = mock(
			LibraryCatalogCleanupService.class);
	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);

	private final LibrarySwitchLauncher launcher = new LibrarySwitchLauncher(executionEnqueueService,
			executionCancellationService, executionPayloadCodec,
			new ExecutionMapper(new ExecutionMessageCodec(new ObjectMapper()), new ExecutionLabels()));

	private final LibrarySwitchJobHandler handler = new LibrarySwitchJobHandler(libraryCatalogCleanupService,
			appSettingService, executionProgressService, executionPayloadCodec);

	@Test
	void aSwitchIsQueuedWithBothLibrariesNamedInItsOwnColumns() {
		Execution queued = capture(() -> launcher.launch("C:/old", "C:/new", "admin@example.com"));

		Assertions.assertThat(queued.getExecutionType()).isEqualTo(ExecutionType.LIBRARY_SWITCH);
		Assertions.assertThat(queued.getSourcePath()).contains("old");
		Assertions.assertThat(queued.getTargetPath()).contains("new");

		// They are the row's own columns because they are what the worker locks: a
		// switch holds the two trees it is replacing, and nothing else.
		Assertions.assertThat(queued.getExecuteFlag()).isTrue();
	}

	/**
	 * Cancelling first and enqueueing second, or the switch would be asking itself
	 * to stop.
	 */
	@Test
	void whatIsRunningIsAskedToStopBeforeTheSwitchIsQueued() {
		capture(() -> launcher.launch("C:/old", "C:/new", "admin@example.com"));

		InOrder order = inOrder(executionCancellationService, executionEnqueueService);

		order.verify(executionCancellationService).requestAllCancellations();
		order.verify(executionEnqueueService).enqueueOrExisting(any());
	}

	/**
	 * A double click, a retried POST and two open tabs are one switch. Two
	 * different switches - to the same library, from different ones - are not.
	 */
	@Test
	void theSameSwitchAskedTwiceIsOneSwitch() {
		String first = capture(() -> launcher.launch("C:/old", "C:/new", "admin")).getDedupKey();
		String same = capture(() -> launcher.launch("C:/old", "C:/new", "someone else")).getDedupKey();
		String other = capture(() -> launcher.launch("C:/another", "C:/new", "admin")).getDedupKey();

		Assertions.assertThat(same).isEqualTo(first);
		Assertions.assertThat(other).isNotEqualTo(first);
	}

	@Test
	void theFirstSwitchOfAllHasNoLibraryToForget() {
		Execution queued = capture(() -> launcher.launch(null, "C:/new", "admin"));

		Assertions.assertThat(queued.getSourcePath()).isNull();
		Assertions.assertThat(queued.getTargetPath()).contains("new");
	}

	@Test
	void aFolderThatIsNotAFolderIsRefusedWhileSomebodyIsLooking(@TempDir Path existing) {
		Assertions.assertThatThrownBy(() -> launcher.validateNewFolder(null))
				.isInstanceOf(IllegalArgumentException.class);
		Assertions.assertThatThrownBy(() -> launcher.validateNewFolder("  "))
				.isInstanceOf(IllegalArgumentException.class);
		String missing = existing.resolve("nope").toString();

		Assertions.assertThatThrownBy(() -> launcher.validateNewFolder(missing))
				.isInstanceOf(IllegalArgumentException.class);

		Assertions.assertThatCode(() -> launcher.validateNewFolder(existing.toString())).doesNotThrowAnyException();

		verify(executionEnqueueService, never()).enqueueOrExisting(any());
	}

	/**
	 * The scenario the whole design exists for: a switch queued for A is claimed
	 * after the library has already become something else. It forgets <em>A</em>,
	 * because A is what the row says - the setting is never consulted to decide
	 * what to delete.
	 */
	@Test
	void aSwitchClaimedLateForgetsTheLibraryItNamedAndNotTheCurrentOne() {
		when(appSettingService.stringValue(eq(SettingsConstants.WATCH_FOLDER), anyString())).thenReturn("C:/C");

		handler.handle(execution(), claimed("C:/A", "C:/B", payload(1)), null);

		verify(libraryCatalogCleanupService).clear("C:/A");
		verify(libraryCatalogCleanupService, never()).clear("C:/C");
		verify(appSettingService).update(SettingsConstants.WATCH_FOLDER, "C:/B", "admin@example.com");

		// Not once, for any reason: reading it would be the beginning of deciding by
		// it.
		verify(appSettingService, never()).stringValue(eq(SettingsConstants.WATCH_FOLDER), anyString());
	}

	/**
	 * The old catalog goes before the setting names the new library. Reversed,
	 * there would be a window in which the screens showed one library's files under
	 * another's name.
	 */
	@Test
	void theOldCatalogIsForgottenBeforeTheSettingNamesTheNewLibrary() {
		handler.handle(execution(), claimed("C:/A", "C:/B", payload(1)), null);

		InOrder order = inOrder(libraryCatalogCleanupService, appSettingService);

		order.verify(libraryCatalogCleanupService).clear("C:/A");
		order.verify(appSettingService).update(eq(SettingsConstants.WATCH_FOLDER), eq("C:/B"), anyString());
	}

	@Test
	void aFirstSwitchWithNothingToForgetStillAdoptsTheNewLibrary() {
		handler.handle(execution(), claimed(null, "C:/B", payload(1)), null);

		verify(libraryCatalogCleanupService, never()).clear(anyString());
		verify(appSettingService).update(eq(SettingsConstants.WATCH_FOLDER), eq("C:/B"), anyString());
	}

	/**
	 * Every step is idempotent - forgetting an already forgotten library deletes
	 * nothing, writing a setting that already holds the value writes the same value
	 * - so a switch interrupted halfway is simply run again from the start.
	 */
	@Test
	void aSwitchIsSafeToRunAgainFromTheStart() {
		Assertions.assertThat(handler.type()).isEqualTo(ExecutionType.LIBRARY_SWITCH);
		Assertions.assertThat(handler.resumable()).isTrue();
		Assertions.assertThat(handler.concurrencyLimit()).isEqualTo(1);

		handler.handle(execution(), claimed("C:/A", "C:/B", payload(1)), null);
		handler.handle(execution(), claimed("C:/A", "C:/B", payload(1)), null);

		verify(libraryCatalogCleanupService, times(2)).clear("C:/A");
		verify(appSettingService, times(2)).update(eq(SettingsConstants.WATCH_FOLDER), eq("C:/B"),
				anyString());
	}

	@Test
	void aPayloadFromAnotherSchemaIsRefusedRatherThanHalfUnderstood() {
		Execution execution = execution();
		ClaimedExecution claimed = claimed("C:/A", "C:/B", payload(99));

		Assertions.assertThatThrownBy(() -> handler.handle(execution, claimed, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema");

		verify(libraryCatalogCleanupService, never()).clear(anyString());
		verify(appSettingService, never()).update(anyString(), anyString(), anyString());
	}

	@Test
	void theSwitchReportsHowManyFilesLeftTheCatalog() {
		when(libraryCatalogCleanupService.clear("C:/A")).thenReturn(1234);

		handler.handle(execution(), claimed("C:/A", "C:/B", payload(1)), null);

		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED), any(), any());
	}

	/**
	 * The queue answers with the row it was handed, stamped as the enqueue would
	 * stamp it. Stubbed once, in a {@code doAnswer}: re-stubbing with {@code when}
	 * calls the mock again, and a test that asks for two switches would run the
	 * answer over a null.
	 */
	@BeforeEach
	void theQueueAcceptsWhateverItIsHanded() {
		doAnswer(call -> {
			Execution queued = call.getArgument(0);

			queued.setStatus(ExecutionStatus.PENDING);

			return queued;
		}).when(executionEnqueueService).enqueueOrExisting(any());
	}

	private Execution capture(Runnable launch) {
		launch.run();

		ArgumentCaptor<Execution> queued = ArgumentCaptor.forClass(Execution.class);

		verify(executionEnqueueService, atLeastOnce()).enqueueOrExisting(queued.capture());

		return queued.getValue();
	}

	private Execution execution() {
		return Execution.builder().id(42L).executionType(ExecutionType.LIBRARY_SWITCH).build();
	}

	private String payload(int schemaVersion) {
		return executionPayloadCodec.encode(new LibrarySwitchPayload(schemaVersion, "admin@example.com"));
	}

	private ClaimedExecution claimed(String oldFolder, String newFolder, String payload) {
		return new ClaimedExecution(42L, ExecutionType.LIBRARY_SWITCH.name(), oldFolder, newFolder, payload);
	}
}