package br.com.jorgemelo.nimbusfilemanager.quarantine.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantinePurgeService;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineService;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineItemResponse;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineDeleteResponse;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantinePurgeResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreBatchResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreOptions;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreRequest;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreSelectedRequest;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.ConflictResolution;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;

class QuarantineWebControllerTest {

	private final QuarantineService quarantineService = mock(QuarantineService.class);
	private final QuarantinePurgeService quarantinePurgeService = mock(QuarantinePurgeService.class);
	private final UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
	private final QuarantineWebController controller = new QuarantineWebController(quarantineService,
			quarantinePurgeService, preferences);

	@Test
	void mapsConflictStringAndDestinationFolderToRestoreOptions() {
		UUID movementId = UUID.randomUUID();

		when(quarantineService.restore(eq(movementId), any()))
				.thenReturn(new QuarantineRestoreResult(true, "RESTORED", "ok", movementId, "C:\\dest\\a.jpg"));

		controller.restore(new QuarantineRestoreRequest(movementId, "rename", "C:\\dest"));

		ArgumentCaptor<QuarantineRestoreOptions> captor = ArgumentCaptor.forClass(QuarantineRestoreOptions.class);

		verify(quarantineService).restore(eq(movementId), captor.capture());

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
		Assertions.assertThat(controller.restore(null).success()).isFalse();
		Assertions.assertThat(controller.restoreSelected(null).total()).isZero();
		Assertions.assertThat(controller.deleteSelected(null).purged()).isZero();

		verify(quarantineService, never()).restore(any(), any());
		verify(quarantineService, never()).restoreMany(any());
		verify(quarantinePurgeService, never()).purgeSelected(any());
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

		verify(quarantineService, times(3)).restore(eq(movementId), captor.capture());

		Assertions.assertThat(captor.getAllValues()).allSatisfy(options -> {
			Assertions.assertThat(options.conflictResolution()).isEqualTo(ConflictResolution.BLOCK);
			Assertions.assertThat(options.destinationFolder()).isNull();
		});
	}

	@Test
	void restoreSelectedForwardsIdsToService() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();

		when(quarantineService.restoreMany(List.of(a, b)))
				.thenReturn(new QuarantineRestoreBatchResult(true, 2, 2, 0, 0, 0, 0, "ok", List.of()));

		controller.restoreSelected(new QuarantineRestoreSelectedRequest(List.of(a, b)));

		verify(quarantineService).restoreMany(List.of(a, b));
	}

	@Test
	void deleteSelectedForwardsIdsToPurgeService() {
		UUID a = UUID.randomUUID();

		when(quarantinePurgeService.purgeSelected(List.of(a)))
				.thenReturn(new QuarantinePurgeResult(1, 1, 1, 0, 0, 0));

		controller.deleteSelected(new QuarantineRestoreSelectedRequest(List.of(a)));

		verify(quarantinePurgeService).purgeSelected(List.of(a));
	}

	/**
	 * A delete that deleted nothing has to say why: the screen shows the sentence
	 * in a dialog, and a status line of bare counters used to read as success while
	 * a running conversion held every file.
	 */
	@Test
	void deleteSelectedExplainsWhenAnotherOperationIsHoldingTheFiles() {
		UUID a = UUID.randomUUID();

		when(quarantinePurgeService.purgeSelected(List.of(a)))
				.thenReturn(new QuarantinePurgeResult(1, 0, 0, 0, 1, 0));

		QuarantineDeleteResponse response = controller.deleteSelected(new QuarantineRestoreSelectedRequest(List.of(a)));

		Assertions.assertThat(response.purged()).isZero();
		Assertions.assertThat(response.busy()).isEqualTo(1);
		Assertions.assertThat(response.message()).contains("em uso por outra operação");
	}

	/** A file the disk refused to delete is reported, not swallowed. */
	@Test
	void deleteSelectedExplainsWhenTheDiskRefusedTheDelete() {
		UUID a = UUID.randomUUID();

		when(quarantinePurgeService.purgeSelected(List.of(a)))
				.thenReturn(new QuarantinePurgeResult(1, 0, 0, 0, 0, 1));

		QuarantineDeleteResponse response = controller.deleteSelected(new QuarantineRestoreSelectedRequest(List.of(a)));

		Assertions.assertThat(response.errors()).isEqualTo(1);
		Assertions.assertThat(response.message()).contains("Não foi possível apagar");
	}

	/**
	 * An item that left quarantine between the listing and the click is not an
	 * error, but the user still has to learn why nothing happened.
	 */
	@Test
	void deleteSelectedExplainsWhenTheItemLeftQuarantineMeanwhile() {
		UUID a = UUID.randomUUID();

		when(quarantinePurgeService.purgeSelected(List.of(a)))
				.thenReturn(new QuarantinePurgeResult(1, 0, 0, 1, 0, 0));

		QuarantineDeleteResponse response = controller.deleteSelected(new QuarantineRestoreSelectedRequest(List.of(a)));

		Assertions.assertThat(response.skipped()).isEqualTo(1);
		Assertions.assertThat(response.message()).contains("deixaram a quarentena");
	}

	/** Nothing to interrupt the user with when the delete did what was asked. */
	@Test
	void deleteSelectedCarriesNoMessageWhenEverythingWasDeleted() {
		UUID a = UUID.randomUUID();

		when(quarantinePurgeService.purgeSelected(List.of(a)))
				.thenReturn(new QuarantinePurgeResult(1, 1, 1, 0, 0, 0));

		QuarantineDeleteResponse response = controller.deleteSelected(new QuarantineRestoreSelectedRequest(List.of(a)));

		Assertions.assertThat(response.purged()).isEqualTo(1);
		Assertions.assertThat(response.message()).isNull();
	}

	@Test
	void cleanupAbsentDelegatesAndReturnsCount() {
		when(quarantinePurgeService.cleanupAbsent()).thenReturn(3);

		Assertions.assertThat(controller.cleanupAbsent().removed()).isEqualTo(3);

		verify(quarantinePurgeService).cleanupAbsent();
	}

	@Test
	void rejectsDeleteSelectedWithoutIds() {
		QuarantineDeleteResponse result = controller.deleteSelected(new QuarantineRestoreSelectedRequest(List.of()));

		Assertions.assertThat(result.purged()).isZero();
	}

	@Test
	void rejectsRestoreSelectedWithoutIds() {
		QuarantineRestoreBatchResult result = controller
				.restoreSelected(new QuarantineRestoreSelectedRequest(List.of()));

		Assertions.assertThat(result.success()).isFalse();
	}

	@Test
	void listsItemsWithDefaultViewMode() {
		Authentication authentication = mock(Authentication.class);

		when(authentication.getName()).thenReturn("tester");
		when(preferences.find("tester", QuarantineConstants.PAGE_KEY)).thenReturn(Map.of());
		when(quarantineService.list(any())).thenReturn(new PageImpl<>(List.of(item("a.jpg"), item("b.jpg"))));

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
		when(quarantineService.list(any())).thenReturn(Page.empty());

		Model savedModel = new ExtendedModelMap();

		controller.quarantine(2, null, null, authentication, savedModel);

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

		verify(quarantineService).list(pageable.capture());

		Assertions.assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
		Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(200);

		Model requestedModel = new ExtendedModelMap();

		controller.quarantine(0, null, 100, authentication, requestedModel);

		verify(preferences).save("tester", QuarantineConstants.PAGE_KEY, SharedConstants.PAGE_SIZE_KEY,
				"100");

		Assertions.assertThat(requestedModel.getAttribute("pageSize")).isEqualTo(100);
	}

	@Test
	void fallsBackForInvalidRequestAndNullPreferences() {
		when(preferences.find(null, QuarantineConstants.PAGE_KEY)).thenReturn(null);
		when(quarantineService.list(any()))
				.thenReturn(new PageImpl<>(List.of(item("missing.jpg", false)), PageRequest.of(0, 50), 101));

		Model model = new ExtendedModelMap();

		controller.quarantine(-3, "invalid", 999, null, model);

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

		verify(quarantineService).list(pageable.capture());

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
		when(quarantineService.list(any())).thenReturn(Page.empty());

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
		when(quarantineService.list(any())).thenReturn(Page.empty());

		Model model = new ExtendedModelMap();

		controller.quarantine(0, null, null, null, model);

		Assertions.assertThat(model.getAttribute("pageSize")).isEqualTo(50);
	}

	private QuarantineItemResponse item(String name) {
		return item(name, true);
	}

	private QuarantineItemResponse item(String name, boolean presentInQuarantine) {
		return new QuarantineItemResponse(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), name,
				"C:\\lib\\" + name, "C:\\lib", "C:\\trash\\" + name, "C:\\trash", 100L, "100 B", LocalDateTime.now(),
				presentInQuarantine, true, false, "PHOTO", "bi-file-earmark-image-fill image", "filetype.image", true,
						false,
				false, false, false, "/api/media/x/content");
	}
}