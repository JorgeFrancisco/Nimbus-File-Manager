package br.com.jorgemelo.nimbusfilemanager.quarantine.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineCleanupLauncher;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineLauncherService;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineListing;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineProgressService;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineRestoreLauncher;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineCleanupResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineItemResponse;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineProgress;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreOptions;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreRequest;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreSelectedRequest;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.ConflictResolution;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;

class QuarantineWebControllerTest {

	private final QuarantineListing quarantineListing = mock(QuarantineListing.class);
	private final QuarantineRestoreLauncher quarantineRestoreLauncher = mock(QuarantineRestoreLauncher.class);
	private final QuarantineCleanupLauncher quarantineCleanupLauncher = mock(QuarantineCleanupLauncher.class);
	private final QuarantineLauncherService quarantineLauncherService = mock(QuarantineLauncherService.class);
	private final QuarantineProgressService quarantineProgressService = mock(QuarantineProgressService.class);
	private final UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
	private final QuarantineWebController controller = new QuarantineWebController(quarantineListing,
			quarantineRestoreLauncher, quarantineCleanupLauncher, quarantineLauncherService, quarantineProgressService,
			preferences);

	@Test
	void mapsConflictStringAndDestinationFolderToRestoreOptions() {
		UUID movementId = UUID.randomUUID();

		when(quarantineRestoreLauncher.restore(eq(movementId), any()))
				.thenReturn(new QuarantineRestoreResult(true, "RESTORED", "ok", movementId, "C:\\dest\\a.jpg"));

		controller.restore(new QuarantineRestoreRequest(movementId, "rename", "C:\\dest"));

		ArgumentCaptor<QuarantineRestoreOptions> captor = ArgumentCaptor.forClass(QuarantineRestoreOptions.class);

		verify(quarantineRestoreLauncher).restore(eq(movementId), captor.capture());

		Assertions.assertThat(captor.getValue().conflictResolution()).isEqualTo(ConflictResolution.RENAME);
		Assertions.assertThat(captor.getValue().destinationFolder()).isNotNull();
	}

	@Test
	void rejectsRestoreWithoutMovementId() {
		QuarantineRestoreResult result = controller.restore(new QuarantineRestoreRequest(null, null, null));

		Assertions.assertThat(result.success()).isFalse();
		Assertions.assertThat(result.outcome()).isEqualTo("ERROR");
	}

	/**
	 * An absent request body deserializes to null, so every write endpoint has to
	 * answer with its own empty result instead of dereferencing it.
	 */
	@Test
	void rejectsEveryWriteEndpointWhenTheRequestBodyIsMissing() {
		when(quarantineProgressService.snapshot()).thenReturn(idle());

		Assertions.assertThat(controller.restore(null).success()).isFalse();
		Assertions.assertThat(controller.restoreSelected(null).running()).isFalse();
		Assertions.assertThat(controller.deleteSelected(null).running()).isFalse();

		verify(quarantineRestoreLauncher, never()).restore(any(), any());
		verify(quarantineLauncherService, never()).launchRestore(any());
		verify(quarantineLauncherService, never()).launchPurge(any());
	}

	/**
	 * The conflict choice arrives as a free string from the screen: anything the
	 * enum does not know falls back to blocking, which is the safe option because
	 * it never overwrites a file.
	 */
	@Test
	void fallsBackToBlockingForAnAbsentOrUnknownConflictChoice() {
		UUID movementId = UUID.randomUUID();

		controller.restore(new QuarantineRestoreRequest(movementId, null, null));
		controller.restore(new QuarantineRestoreRequest(movementId, "   ", "   "));
		controller.restore(new QuarantineRestoreRequest(movementId, "overwrite-everything", null));

		ArgumentCaptor<QuarantineRestoreOptions> captor = ArgumentCaptor.forClass(QuarantineRestoreOptions.class);

		verify(quarantineRestoreLauncher, times(3)).restore(eq(movementId), captor.capture());

		Assertions.assertThat(captor.getAllValues()).allSatisfy(options -> {
			Assertions.assertThat(options.conflictResolution()).isEqualTo(ConflictResolution.BLOCK);
			Assertions.assertThat(options.destinationFolder()).isNull();
		});
	}

	/**
	 * The screen no longer waits on the answer: the request queues the batch and
	 * comes back with what the poller will keep asking for.
	 */
	@Test
	void restoreSelectedQueuesTheSelectionAndAnswersWithTheCurrentBatch() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();

		when(quarantineProgressService.snapshot()).thenReturn(running("QUARANTINE_RESTORE", 2));

		QuarantineProgress progress = controller.restoreSelected(new QuarantineRestoreSelectedRequest(List.of(a, b)));

		verify(quarantineLauncherService).launchRestore(List.of(a, b));

		Assertions.assertThat(progress.running()).isTrue();
		Assertions.assertThat(progress.total()).isEqualTo(2);
	}

	/**
	 * The request could not be made - no quarantine folder is configured - and what
	 * the person gets is the sentence saying so, in the snapshot the screen already
	 * polls.
	 *
	 * <p>
	 * It has to arrive as data rather than as an exception because this controller
	 * serves the screen, and the advice that turns a refusal into a message covers
	 * the REST packages. Left to escape, a quarantine folder somebody never set
	 * would reach them as a bare 500.
	 */
	@Test
	void answersWithTheReasonWhenTheBatchCouldNotBeQueuedAtAll() {
		UUID picked = UUID.randomUUID();

		doThrow(new IllegalArgumentException("the quarantine folder is not configured"))
				.when(quarantineLauncherService).launchPurge(List.of(picked));
		when(quarantineProgressService.refused("the quarantine folder is not configured"))
				.thenReturn(new QuarantineProgress(false, null, 0, 0, 0, 0, "the quarantine folder is not configured"));

		QuarantineProgress progress = controller.deleteSelected(new QuarantineRestoreSelectedRequest(List.of(picked)));

		Assertions.assertThat(progress.running()).isFalse();
		Assertions.assertThat(progress.message()).isEqualTo("the quarantine folder is not configured");

		verify(quarantineProgressService, never()).snapshot();
	}

	@Test
	void deleteSelectedQueuesThePurge() {
		UUID a = UUID.randomUUID();

		when(quarantineProgressService.snapshot()).thenReturn(running("QUARANTINE_PURGE", 1));

		QuarantineProgress progress = controller.deleteSelected(new QuarantineRestoreSelectedRequest(List.of(a)));

		verify(quarantineLauncherService).launchPurge(List.of(a));

		Assertions.assertThat(progress.executionType()).isEqualTo("QUARANTINE_PURGE");
	}

	/**
	 * The endpoint hands back whatever the launcher answered, sentence included:
	 * the screen has three different things to say and none of them is assembled
	 * here.
	 */
	@Test
	void cleanupAbsentAnswersWithWhatTheLauncherDecided() {
		when(quarantineCleanupLauncher.clearAbsent())
				.thenReturn(QuarantineCleanupResult.cleared(3, "3 removidos"));

		QuarantineCleanupResult result = controller.cleanupAbsent();

		Assertions.assertThat(result.removed()).isEqualTo(3);
		Assertions.assertThat(result.pending()).isFalse();
		Assertions.assertThat(result.message()).isEqualTo("3 removidos");

		verify(quarantineCleanupLauncher).clearAbsent();
	}

	/** An empty selection queues nothing - there is no work to ask for. */
	@Test
	void queuesNothingWhenNoItemWasSelected() {
		when(quarantineProgressService.snapshot()).thenReturn(idle());

		Assertions.assertThat(controller.deleteSelected(new QuarantineRestoreSelectedRequest(List.of())).running())
				.isFalse();
		Assertions.assertThat(controller.restoreSelected(new QuarantineRestoreSelectedRequest(List.of())).running())
				.isFalse();

		verify(quarantineLauncherService, never()).launchRestore(any());
		verify(quarantineLauncherService, never()).launchPurge(any());
	}

	/**
	 * What the page polls while the worker works, and what it reads once the row
	 * is closed - the same snapshot either way.
	 */
	@Test
	void progressAnswersWithTheLatestBatch() {
		when(quarantineProgressService.snapshot()).thenReturn(running("QUARANTINE_PURGE", 4));

		Assertions.assertThat(controller.progress().total()).isEqualTo(4);
	}

	@Test
	void listsItemsWithDefaultViewMode() {
		Authentication authentication = mock(Authentication.class);

		when(authentication.getName()).thenReturn("tester");
		when(preferences.find("tester", QuarantineConstants.PAGE_KEY)).thenReturn(Map.of());
		when(quarantineListing.list(any())).thenReturn(new PageImpl<>(List.of(item("a.jpg"), item("b.jpg"))));

		Model model = new ExtendedModelMap();

		String view = controller.quarantine(0, null, null, authentication, model);

		Assertions.assertThat(view).isEqualTo("app/quarantine");
		Assertions.assertThat(model.getAttribute("viewMode")).isEqualTo("details");

		@SuppressWarnings("unchecked")
		List<QuarantineItemResponse> items = (List<QuarantineItemResponse>) model.getAttribute("items");

		Assertions.assertThat(items).hasSize(2);
		Assertions.assertThat(model.getAttribute("pageSize")).isEqualTo(50);
		Assertions.assertThat(model.getAttribute("pageSizes")).isEqualTo(List.of(50, 100, 200));
	}

	@Test
	void usesSavedPageSizeAndPersistsAValidRequestedSize() {
		Authentication authentication = mock(Authentication.class);

		when(authentication.getName()).thenReturn("tester");
		when(preferences.find("tester", QuarantineConstants.PAGE_KEY))
				.thenReturn(Map.of(SharedConstants.PAGE_SIZE_KEY, "200"));
		when(quarantineListing.list(any())).thenReturn(Page.empty());

		Model savedModel = new ExtendedModelMap();

		controller.quarantine(2, null, null, authentication, savedModel);

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

		verify(quarantineListing).list(pageable.capture());

		Assertions.assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
		Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(200);

		Model requestedModel = new ExtendedModelMap();

		controller.quarantine(0, null, 100, authentication, requestedModel);

		verify(preferences).save("tester", QuarantineConstants.PAGE_KEY, SharedConstants.PAGE_SIZE_KEY, "100");

		Assertions.assertThat(requestedModel.getAttribute("pageSize")).isEqualTo(100);
	}

	@Test
	void fallsBackForInvalidRequestAndNullPreferences() {
		when(preferences.find(null, QuarantineConstants.PAGE_KEY)).thenReturn(null);
		when(quarantineListing.list(any()))
				.thenReturn(new PageImpl<>(List.of(item("missing.jpg", false)), PageRequest.of(0, 50), 101));

		Model model = new ExtendedModelMap();

		controller.quarantine(-3, "invalid", 999, null, model);

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

		verify(quarantineListing).list(pageable.capture());

		Assertions.assertThat(pageable.getValue().getPageNumber()).isZero();
		Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
		Assertions.assertThat(model.getAttribute("viewMode")).isEqualTo("details");
		Assertions.assertThat(model.getAttribute("hasAbsent")).isEqualTo(true);
		Assertions.assertThat(model.getAttribute("hasNext")).isEqualTo(true);
		Assertions.assertThat(model.getAttribute("totalPages")).isEqualTo(3);
	}

	@Test
	void ignoresMalformedSavedSizeAndPersistsAValidView() {
		Authentication authentication = mock(Authentication.class);

		when(authentication.getName()).thenReturn("tester");
		when(preferences.find("tester", QuarantineConstants.PAGE_KEY))
				.thenReturn(Map.of(SharedConstants.PAGE_SIZE_KEY, "not-a-number"));
		when(quarantineListing.list(any())).thenReturn(Page.empty());

		Model model = new ExtendedModelMap();

		controller.quarantine(0, "large", null, authentication, model);

		verify(preferences).save("tester", QuarantineConstants.PAGE_KEY, "view", "large");

		Assertions.assertThat(model.getAttribute("viewMode")).isEqualTo("large");
		Assertions.assertThat(model.getAttribute("pageSize")).isEqualTo(50);
	}

	@Test
	void ignoresBlankSavedPageSize() {
		when(preferences.find(null, QuarantineConstants.PAGE_KEY))
				.thenReturn(Map.of(SharedConstants.PAGE_SIZE_KEY, " "));
		when(quarantineListing.list(any())).thenReturn(Page.empty());

		Model model = new ExtendedModelMap();

		controller.quarantine(0, null, null, null, model);

		Assertions.assertThat(model.getAttribute("pageSize")).isEqualTo(50);
	}

	private QuarantineProgress idle() {
		return new QuarantineProgress(false, null, 0, 0, 0, 0, null);
	}

	private QuarantineProgress running(String executionType, int total) {
		return new QuarantineProgress(true, executionType, total, 0, 0, 0, null);
	}

	private QuarantineItemResponse item(String name) {
		return item(name, true);
	}

	private QuarantineItemResponse item(String name, boolean presentInQuarantine) {
		return new QuarantineItemResponse(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), name,
				"C:\\lib\\" + name, "C:\\lib", "C:\\trash\\" + name, "C:\\trash", 100L, "100 B", LocalDateTime.now(),
				presentInQuarantine, true, false, "PHOTO", "bi-file-earmark-image-fill image", "filetype.image", true,
				false, false, false, false, "/api/media/x/content");
	}
}