package br.com.jorgemelo.nimbusfilemanager.organization.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.ui.ExtendedModelMap;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationFallbackMode;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationSubdivision;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationForm;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationItem;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.StoredPlanPage;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationSummary;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SizeFormatter;

class OrganizationWebControllerTest {

	private static final LocalDateTime NOW = LocalDateTime.parse("2026-07-08T12:00:00");

	@TempDir
	private Path tempDir;

	@Test
	void organizationShouldDispatchPreviewAndExecuteAsyncAndRedirectToProgress() throws Exception {
		OrganizationService organizationService = mock(OrganizationService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), mock(ExecutionQueryService.class));
		ExtendedModelMap previewModel = new ExtendedModelMap();
		ExtendedModelMap executeModel = new ExtendedModelMap();
		ExecutionResponse previewStarted = execution();
		ExecutionResponse executeStarted = execution();
		Path source = Files.createDirectories(tempDir.resolve("organization-source"));
		Path target = tempDir.resolve("organization-target");

		when(organizationService.previewAsync(any())).thenReturn(previewStarted);
		when(organizationService.executeAsync(any())).thenReturn(executeStarted);

		Assertions
				.assertThat(controller.preview(
						orgForm(source.toString(), target.toString(), true, OrganizationLayout.DEFAULT, 100, 0, 20),
						previewModel))
				.isEqualTo("redirect:/app/progress/" + previewStarted.executionId() + "?kind=organization-preview");
		Assertions
				.assertThat(controller.execute(
						orgForm(source.toString(), target.toString(), true, OrganizationLayout.DEFAULT, 100, 0, 50),
						executeModel))
				.isEqualTo("redirect:/app/progress/" + executeStarted.executionId() + "?kind=organization-execute");
		verify(organizationService).previewAsync(any());
		verify(organizationService).executeAsync(any());
	}

	@Test
	void organizationShouldReadAndSaveDefaultsFromUserPreferences() throws Exception {
		OrganizationService organizationService = mock(OrganizationService.class);
		UserPagePreferenceService userPagePreferenceService = mock(UserPagePreferenceService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				userPagePreferenceService, mock(ExecutionQueryService.class));
		TestingAuthenticationToken authentication = new TestingAuthenticationToken("admin@example.com", "password");
		ExtendedModelMap getModel = new ExtendedModelMap();
		ExtendedModelMap previewModel = new ExtendedModelMap();
		Path source = Files.createDirectories(tempDir.resolve("preferences-source"));
		Path target = tempDir.resolve("preferences-target");

		when(userPagePreferenceService.find("admin@example.com", "organization"))
				.thenReturn(Map.of("recursive", "false", "layout", "YEAR_MONTH_DAY", "size", "100"));
		when(organizationService.previewAsync(any())).thenReturn(execution());

		controller.organization(authentication, getModel);

		Assertions.assertThat(getModel).containsEntry("recursive", false)
				.containsEntry("layoutValue", OrganizationLayout.YEAR_MONTH_DAY).containsEntry("size", 100);

		controller.preview(
				orgForm(source.toString(), target.toString(), true, OrganizationLayout.YEAR_MONTH_DAY, 100, 0, 20),
				authentication, previewModel);

		verify(userPagePreferenceService).save("admin@example.com", "organization", "recursive", "true");
		verify(userPagePreferenceService).save("admin@example.com", "organization", "layout", "YEAR_MONTH_DAY");
		verify(userPagePreferenceService).save("admin@example.com", "organization", "size", "20");
		verify(userPagePreferenceService).save("admin@example.com", "organization", "limit", "100");
	}

	@Test
	void organizationShouldShowErrorWhenServiceRejectsPreview() throws Exception {
		OrganizationService organizationService = mock(OrganizationService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), mock(ExecutionQueryService.class));
		ExtendedModelMap model = new ExtendedModelMap();
		Path source = Files.createDirectories(tempDir.resolve("same-source"));
		Path target = tempDir.resolve("target");

		when(organizationService.previewAsync(any())).thenThrow(new IllegalArgumentException("Business error."));

		String view = controller.preview(
				orgForm(source.toString(), target.toString(), true, OrganizationLayout.DEFAULT, 100, 0, 50), model);

		Assertions.assertThat(view).isEqualTo("app/organization");
		Assertions.assertThat(model).containsEntry("error", "Business error.")
				.containsEntry("sourcePath", source.toString()).containsEntry("targetPath", target.toString());
	}

	@Test
	void organizationPrefillsSourceAndTargetWhenReprocessing() {
		OrganizationService organizationService = mock(OrganizationService.class);
		var preferences = mock(UserPagePreferenceService.class);
		when(preferences.find(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Map.of());
		OrganizationWebController controller = new OrganizationWebController(organizationService, preferences,
				mock(ExecutionQueryService.class));
		ExtendedModelMap model = new ExtendedModelMap();

		controller.organization("D:/SEPARAR/ORGANIZADOS", "D:/SEPARAR/ORGANIZADOS 2", null, model);

		Assertions.assertThat(model).containsEntry("sourcePath", "D:/SEPARAR/ORGANIZADOS").containsEntry("targetPath",
				"D:/SEPARAR/ORGANIZADOS 2");
	}

	@Test
	void organizationPreviewResultShouldRenderStoredPlanOrErrorWhenMissing() {
		OrganizationService organizationService = mock(OrganizationService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), mock(ExecutionQueryService.class));
		ExtendedModelMap foundModel = new ExtendedModelMap();
		ExtendedModelMap missingModel = new ExtendedModelMap();
		StoredPlanPage plan = plan();

		when(organizationService.planPage(eq(1L), anyInt(), anyInt(), anyBoolean())).thenReturn(Optional.of(plan));
		when(organizationService.planPage(eq(2L), anyInt(), anyInt(), anyBoolean())).thenReturn(Optional.empty());

		String foundView = controller.previewResult(1L, 0, 20, foundModel);
		String missingView = controller.previewResult(2L, 0, 20, missingModel);

		Assertions.assertThat(foundView).isEqualTo("app/organization");
		Assertions.assertThat(foundModel).containsEntry("plan", plan).containsEntry("previewItems", plan.items());
		Assertions.assertThat(missingView).isEqualTo("app/organization");
		Assertions.assertThat(missingModel.get("error")).isNotNull();
	}

	@Test
	void organizationPreviewResultByUuidExposesProgressLinkWhenPlanIsMissing() {
		OrganizationService organizationService = mock(OrganizationService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), mock(ExecutionQueryService.class));
		UUID executionId = UUID.randomUUID();
		when(organizationService.planPagePublic(eq(executionId), anyInt(), anyInt(), anyBoolean()))
				.thenReturn(Optional.empty());
		ExtendedModelMap model = new ExtendedModelMap();

		String view = controller.previewResult(executionId, 0, 50, false, null, model);

		Assertions.assertThat(view).isEqualTo("app/organization");
		// The progress path is no longer baked into the message text; the template
		// turns the id into a
		// real link instead.
		Assertions.assertThat(model.get("error").toString()).contains(executionId.toString());
		Assertions.assertThat(model.get("error").toString()).doesNotContain("/app/progress/");
		Assertions.assertThat(model).containsEntry("errorProgressId", executionId);
	}

	@Test
	void organizationPreviewResultByUuidReflectsAnErroredExecutionInsteadOfClaimingItIsProcessing() {
		OrganizationService organizationService = mock(OrganizationService.class);
		var executionQueryService = mock(ExecutionQueryService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), executionQueryService);
		UUID executionId = UUID.randomUUID();
		when(organizationService.planPagePublic(eq(executionId), anyInt(), anyInt(), anyBoolean()))
				.thenReturn(Optional.empty());
		when(executionQueryService.get(executionId)).thenReturn(new ExecutionResponse(executionId, "ORGANIZATION",
				"ERROR", null, null, null, null, null, null, null, null, null, null, null, null, null, null));
		ExtendedModelMap model = new ExtendedModelMap();

		controller.previewResult(executionId, 0, 50, false, null, model);

		Assertions.assertThat(model.get("error").toString()).contains("erro");
		Assertions.assertThat(model.get("error").toString()).doesNotContain("processada");
		Assertions.assertThat(model).containsEntry("errorProgressId", executionId);
	}

	/**
	 * A preview that is gone can be gone for four different reasons, and the user
	 * gets a different sentence for each: it ended, it was cancelled, it is still
	 * running, or nobody ever heard of that id. Saying "still processing" about a
	 * finished run is what sends someone waiting on a screen forever.
	 */
	@Test
	void organizationPreviewResultExplainsEachReasonThePlanIsNoLongerThere() {
		Assertions.assertThat(previewErrorFor("FINISHED")).contains("não está mais disponível");
		Assertions.assertThat(previewErrorFor("CANCELLED")).contains("não está mais disponível");
		Assertions.assertThat(previewErrorFor("RUNNING")).contains("ainda está sendo processada");
		Assertions.assertThat(previewErrorFor("FINISHED_WITH_ERRORS")).contains("terminou com erro");
	}

	private String previewErrorFor(String status) {
		OrganizationService organizationService = mock(OrganizationService.class);
		ExecutionQueryService executionQueryService = mock(ExecutionQueryService.class);

		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), executionQueryService);

		UUID executionId = UUID.randomUUID();

		when(organizationService.planPagePublic(eq(executionId), anyInt(), anyInt(), anyBoolean()))
				.thenReturn(Optional.empty());
		when(executionQueryService.get(executionId)).thenReturn(new ExecutionResponse(executionId, "ORGANIZATION",
				status, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.previewResult(executionId, 0, 50, false, null, model);

		return model.get("error").toString();
	}

	@Test
	void organizationPreviewResultShouldRestoreSavedFormChoicesNotDefaults() {
		OrganizationService organizationService = mock(OrganizationService.class);
		UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService, preferences,
				mock(ExecutionQueryService.class));
		ExtendedModelMap model = new ExtendedModelMap();

		when(organizationService.planPage(1L, 0, 20, false)).thenReturn(Optional.of(plan()));
		when(preferences.find(null, "organization")).thenReturn(Map.of("limit", "250", "recursive", "false"));

		controller.previewResult(1L, 0, 20, model);

		Assertions.assertThat(model).containsEntry("limit", 250).containsEntry("recursive", false);
	}

	@Test
	void organizationShouldRestoreAllSavedFieldsIncludingLocationAndLimit() {
		OrganizationService organizationService = mock(OrganizationService.class);
		UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService, preferences,
				mock(ExecutionQueryService.class));
		TestingAuthenticationToken auth = new TestingAuthenticationToken("admin@example.com", "password");
		ExtendedModelMap model = new ExtendedModelMap();

		when(preferences.find("admin@example.com", "organization"))
				.thenReturn(Map.ofEntries(Map.entry("recursive", "false"), Map.entry("layout", "YEAR_MONTH_DAY"),
						Map.entry("size", "100"), Map.entry("limit", "250"), Map.entry("allowConflicts", "true"),
						Map.entry("overwriteExisting", "true"), Map.entry("locationSubdivision", "COUNTRY_STATE_CITY"),
						Map.entry("locationMinConfidence", "HIGH"), Map.entry("locationFallback", "FALLBACK_FOLDER")));

		controller.organization(auth, model);

		Assertions.assertThat(model).containsEntry("limit", 250).containsEntry("recursive", false)
				.containsEntry("allowConflicts", true).containsEntry("overwriteExisting", true)
				.containsEntry("locationSubdivisionValue", LocationSubdivision.COUNTRY_STATE_CITY)
				.containsEntry("locationMinConfidenceValue", LocationConfidence.HIGH)
				.containsEntry("locationFallbackValue", LocationFallbackMode.FALLBACK_FOLDER);
	}

	@Test
	void organizationShouldFallBackToDefaultsOnInvalidSavedPreferences() {
		OrganizationService organizationService = mock(OrganizationService.class);
		UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService, preferences,
				mock(ExecutionQueryService.class));
		ExtendedModelMap model = new ExtendedModelMap();

		when(preferences.find(null, "organization")).thenReturn(Map.ofEntries(Map.entry("limit", "not-a-number"),
				Map.entry("size", "bad"), Map.entry("layout", "NONEXISTENT"), Map.entry("locationSubdivision", "BOGUS"),
				Map.entry("locationMinConfidence", ""), Map.entry("locationFallback", "BOGUS")));

		controller.organization(model);

		Assertions.assertThat(model).containsEntry("limit", 1000).containsEntry("size", 50)
				.containsEntry("layoutValue", OrganizationLayout.DEFAULT)
				.containsEntry("locationSubdivisionValue", LocationSubdivision.NONE);
		Assertions.assertThat(model.get("locationMinConfidenceValue")).isNull();
		Assertions.assertThat(model).containsEntry("locationFallbackValue", LocationFallbackMode.IGNORE);
	}

	@Test
	void executeShouldPersistLocationAndLimitChoices() throws Exception {
		OrganizationService organizationService = mock(OrganizationService.class);
		UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService, preferences,
				mock(ExecutionQueryService.class));
		TestingAuthenticationToken auth = new TestingAuthenticationToken("admin@example.com", "password");
		Path source = Files.createDirectories(tempDir.resolve("execute-source"));
		Path target = tempDir.resolve("execute-target");

		when(organizationService.executeAsync(any())).thenReturn(execution());

		OrganizationForm form = new OrganizationForm(source.toString(), target.toString(), true,
				OrganizationLayout.DEFAULT, 250, true, false, 0, null, LocationSubdivision.COUNTRY_STATE,
				LocationConfidence.MEDIUM, LocationFallbackMode.FALLBACK_FOLDER);

		controller.execute(form, auth, new ExtendedModelMap());

		verify(preferences).save("admin@example.com", "organization", "limit", "250");
		verify(preferences).save("admin@example.com", "organization", "locationSubdivision", "COUNTRY_STATE");
		verify(preferences).save("admin@example.com", "organization", "locationMinConfidence", "MEDIUM");
		verify(preferences).save("admin@example.com", "organization", "locationFallback", "FALLBACK_FOLDER");
		verify(preferences).save("admin@example.com", "organization", "allowConflicts", "true");
	}

	/**
	 * The other half of the same screen: a form the user left on its defaults saves
	 * only what it actually carries. Saving a null as a value would overwrite the
	 * choice they made last time with nothing.
	 *
	 * <p>
	 * The exception is the minimum confidence, where "Qualquer" <em>is</em> a
	 * choice and has to overwrite a stricter one saved before - so it is written as
	 * an empty value rather than skipped.
	 */
	@Test
	void executeSavesOnlyTheChoicesTheFormActuallyCarries() throws Exception {
		OrganizationService organizationService = mock(OrganizationService.class);
		UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService, preferences,
				mock(ExecutionQueryService.class));
		TestingAuthenticationToken auth = new TestingAuthenticationToken("admin@example.com", "password");
		Path source = Files.createDirectories(tempDir.resolve("bare-source"));
		Path target = tempDir.resolve("bare-target");

		when(organizationService.executeAsync(any())).thenReturn(execution());

		OrganizationForm form = new OrganizationForm(source.toString(), target.toString(), true,
				OrganizationLayout.DEFAULT, null, true, false, 0, null, null, null, null);

		controller.execute(form, auth, new ExtendedModelMap());

		verify(preferences, never()).save(any(), any(), eq("limit"), any());
		verify(preferences, never()).save(any(), any(), eq("locationSubdivision"), any());
		verify(preferences, never()).save(any(), any(), eq("locationFallback"), any());
		verify(preferences).save("admin@example.com", "organization", "locationMinConfidence", "");
	}

	/**
	 * The paging moved into the query, so what the screen still decides is what the
	 * arrows do. The page it renders is the page it was handed - twenty rows of a
	 * plan of twenty-five - and this pins that it derives "there is a next page"
	 * from the plan's own total rather than from the rows in front of it.
	 */
	@Test
	void previewResultDerivesThePagingControlsFromTheStoredTotal() {
		OrganizationService organizationService = mock(OrganizationService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), mock(ExecutionQueryService.class));

		ExtendedModelMap firstPageModel = new ExtendedModelMap();
		ExtendedModelMap lastPageModel = new ExtendedModelMap();

		when(organizationService.planPage(1L, 0, 20, false)).thenReturn(Optional.of(pageOf(items(20), 0, 20, 25)));
		when(organizationService.planPage(1L, 1, 20, false)).thenReturn(Optional.of(pageOf(items(5), 1, 20, 25)));

		controller.previewResult(1L, 0, 20, firstPageModel);
		controller.previewResult(1L, 1, 20, lastPageModel);

		Assertions.assertThat(firstPageModel).containsEntry("totalPages", 2).containsEntry("hasPrevious", false)
				.containsEntry("hasNext", true);
		Assertions.assertThat(lastPageModel).containsEntry("hasPrevious", true).containsEntry("hasNext", false);
	}

	/**
	 * "Only conflicts" is a different query now rather than a filter over rows this
	 * process holds - so what the screen has to get right is passing the flag on
	 * and rendering the totals that come back.
	 */
	@Test
	void previewResultWithOnlyConflictsAsksTheQueryForThem() {
		OrganizationService organizationService = mock(OrganizationService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), mock(ExecutionQueryService.class));
		ExtendedModelMap model = new ExtendedModelMap();

		UUID executionId = UUID.randomUUID();

		when(organizationService.planPagePublic(executionId, 0, 20, true))
				.thenReturn(Optional.of(pageOf(items(3), 0, 20, 3)));

		controller.previewResult(executionId, 0, 20, true, null, model);

		Assertions.assertThat(model).containsEntry("onlyConflicts", true).containsEntry("totalItems", 3);
		Assertions.assertThat((List<?>) model.get("previewItems")).hasSize(3);

		verify(organizationService, never()).planPagePublic(executionId, 0, 20, false);
	}

	/**
	 * The plan being stale is information the screen shows, not a refusal. The run
	 * recalculates either way; what this holds is that the user is told.
	 */
	@Test
	void previewResultSaysWhenTheCatalogMovedSinceThePlanWasBuilt() {
		OrganizationService organizationService = mock(OrganizationService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), mock(ExecutionQueryService.class));

		UUID executionId = UUID.randomUUID();

		when(organizationService.planPagePublic(executionId, 0, 50, false)).thenReturn(Optional.of(new StoredPlanPage(
				"C:/in", "C:/out", OrganizationLayout.DEFAULT, new OrganizationSummary(1, 0, 0, 0, 1, 0, 0, 0, 0),
				true, items(1), 0, 50, 1)));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.previewResult(executionId, 0, 50, false, null, model);

		Assertions.assertThat(model).containsEntry("catalogChanged", true);
		Assertions.assertThat(model.get("error")).isNull();
	}

	@Test
	void previewResultShouldExposeLocalizedConflictTypeLabelsByEnumName() {
		OrganizationService organizationService = mock(OrganizationService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), mock(ExecutionQueryService.class));
		ExtendedModelMap model = new ExtendedModelMap();

		when(organizationService.planPage(1L, 0, 20, false)).thenReturn(Optional.of(plan()));

		controller.previewResult(1L, 0, 20, model);

		@SuppressWarnings("unchecked")
		Map<String, String> labels = (Map<String, String>) model.get("conflictTypeLabels");

		Assertions.assertThat(labels).containsEntry("TARGET_EXISTS", "Já existe no destino")
				.containsEntry("DUPLICATE_TARGET", "Duplicado no destino")
				.containsEntry("TARGET_EXISTS_AND_DUPLICATE", "Já existe e duplicado").hasSize(3);
	}

	@Test
	void previewAndExecuteShouldRejectInvalidSourceAndTargetPaths() throws Exception {
		OrganizationService organizationService = mock(OrganizationService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), mock(ExecutionQueryService.class));
		Path source = Files.createDirectories(tempDir.resolve("valid-source"));

		String blankSource = controller.preview(orgForm("  ", source.toString(), true, null, 100, 0, 50),
				new ExtendedModelMap());
		String blankTarget = controller.preview(orgForm(source.toString(), " ", true, null, 100, 0, 50),
				new ExtendedModelMap());
		String missingSource = controller.preview(
				orgForm(tempDir.resolve("does-not-exist").toString(), source.toString(), true, null, 100, 0, 50),
				new ExtendedModelMap());
		String samePath = controller.preview(orgForm(source.toString(), source.toString(), true, null, 100, 0, 50),
				new ExtendedModelMap());
		ExtendedModelMap executeSamePathModel = new ExtendedModelMap();
		String executeSamePath = controller
				.execute(orgForm(source.toString(), source.toString(), true, null, 100, 0, 50), executeSamePathModel);

		Assertions.assertThat(blankSource).isEqualTo("app/organization");
		Assertions.assertThat(blankTarget).isEqualTo("app/organization");
		Assertions.assertThat(missingSource).isEqualTo("app/organization");
		Assertions.assertThat(samePath).isEqualTo("app/organization");
		Assertions.assertThat(executeSamePath).isEqualTo("app/organization");
		Assertions.assertThat(executeSamePathModel).containsEntry("error",
				"A pasta de origem e destino devem ser diferentes.");
		Mockito.verifyNoInteractions(organizationService);
	}

	@Test
	void previewAndExecuteShouldDefaultNullLayoutToDefault() throws Exception {
		OrganizationService organizationService = mock(OrganizationService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), mock(ExecutionQueryService.class));
		Path source = Files.createDirectories(tempDir.resolve("layout-source"));
		Path target = tempDir.resolve("layout-target");
		ExtendedModelMap previewModel = new ExtendedModelMap();
		ExtendedModelMap executeModel = new ExtendedModelMap();

		when(organizationService.previewAsync(any())).thenReturn(execution());
		when(organizationService.executeAsync(any())).thenReturn(execution());

		controller.preview(orgForm(source.toString(), target.toString(), true, null, 100, 0, 50), previewModel);
		controller.execute(orgForm(source.toString(), target.toString(), true, null, 100, 0, 50), executeModel);

		Assertions.assertThat(previewModel).containsEntry("layoutValue", OrganizationLayout.DEFAULT);
		Assertions.assertThat(executeModel).containsEntry("layoutValue", OrganizationLayout.DEFAULT);
	}

	@Test
	void organizationShouldLoadDefaultFormWithDefaultPageAndSize() {
		OrganizationService organizationService = mock(OrganizationService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), mock(ExecutionQueryService.class));
		ExtendedModelMap model = new ExtendedModelMap();

		String view = controller.organization(model);

		Assertions.assertThat(view).isEqualTo("app/organization");
		Assertions.assertThat(model).containsEntry("page", 0).containsEntry("size", 50);
		Mockito.verifyNoInteractions(organizationService);
	}

	@Test
	void previewShouldNormalizeNullOrNegativePageAndUnsupportedSize() throws Exception {
		OrganizationService organizationService = mock(OrganizationService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), mock(ExecutionQueryService.class));
		Path source = Files.createDirectories(tempDir.resolve("page-source"));
		Path target = tempDir.resolve("page-target");
		ExtendedModelMap nullPageModel = new ExtendedModelMap();
		ExtendedModelMap negativePageModel = new ExtendedModelMap();
		ExtendedModelMap unsupportedSizeModel = new ExtendedModelMap();

		when(organizationService.previewAsync(any())).thenReturn(execution());

		controller.preview(orgForm(source.toString(), target.toString(), true, null, 100, null, null), nullPageModel);
		controller.preview(orgForm(source.toString(), target.toString(), true, null, 100, -1, 50), negativePageModel);
		controller.preview(orgForm(source.toString(), target.toString(), true, null, 100, 0, 999),
				unsupportedSizeModel);

		Assertions.assertThat(nullPageModel).containsEntry("page", 0).containsEntry("size", 50);
		Assertions.assertThat(negativePageModel).containsEntry("page", 0);
		Assertions.assertThat(unsupportedSizeModel).containsEntry("size", 50);
	}

	private StoredPlanPage plan() {
		return new StoredPlanPage("C:/media/input", "C:/media/output", OrganizationLayout.DEFAULT,
				new OrganizationSummary(0, 0, 0, 0, 0, 0, 0, 0, 0), false, List.of(), 0, 50, 0);
	}

	private static OrganizationForm orgForm(String sourcePath, String targetPath, boolean recursive,
			OrganizationLayout layout, Integer limit, Integer page, Integer size) {
		return new OrganizationForm(sourcePath, targetPath, recursive, layout, limit, false, false, page, size, null,
				null, null);
	}

	private List<OrganizationItem> items(int count) {
		List<OrganizationItem> items = new ArrayList<>();

		for (int index = 0; index < count; index++) {
			items.add(new OrganizationItem(null, UUID.randomUUID(), "file" + index + ".jpg",
					"C:/media/input/file" + index + ".jpg", "C:/media/output/file" + index + ".jpg", null, null, null,
					null, null, null, null, 100L, false, false, false, false, false, null, null, null));
		}

		return items;
	}

	private StoredPlanPage pageOf(List<OrganizationItem> items, int page, int size, int totalItems) {
		return new StoredPlanPage("C:/media/input", "C:/media/output", OrganizationLayout.DEFAULT,
				new OrganizationSummary(totalItems, 0, 0, 0, totalItems, 0, 0, 0, 0), false, items, page, size,
				totalItems);
	}

	private ExecutionResponse execution() {
		return new ExecutionResponse(1L, "INVENTORY", "FINISHED", NOW, NOW, "C:/media/input", null, 1, 1, 0, 0, 0, 0,
				null, null, "ok", false);
	}

	/**
	 * A rejected execution keeps the operator on the form with the reason, instead
	 * of redirecting to a progress page for a run that never started.
	 */
	@Test
	void organizationShouldShowErrorWhenServiceRejectsTheExecution() throws Exception {
		OrganizationService organizationService = mock(OrganizationService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), mock(ExecutionQueryService.class));
		ExtendedModelMap model = new ExtendedModelMap();
		Path source = Files.createDirectories(tempDir.resolve("rejected-source"));
		Path target = tempDir.resolve("rejected-target");

		when(organizationService.executeAsync(any())).thenThrow(new IllegalArgumentException("Path outside roots."));

		OrganizationForm form = new OrganizationForm(source.toString(), target.toString(), true,
				OrganizationLayout.DEFAULT, 100, false, false, 0, null, null, null, null);

		String view = controller.execute(form, new TestingAuthenticationToken("admin@example.com", "password"), model);

		Assertions.assertThat(view).isEqualTo("app/organization");
		Assertions.assertThat(model).containsEntry("error", "Path outside roots.");
	}

	/**
	 * Before a preview nothing has been counted, so the question cannot promise a
	 * number - and inventing one would be worse than admitting it. What it can do
	 * is point at the preview, which is where the number comes from.
	 */
	@Test
	void theExecuteConfirmationAdmitsItDoesNotKnowTheSizeBeforeAPreview() {
		OrganizationWebController controller = new OrganizationWebController(mock(OrganizationService.class),
				mock(UserPagePreferenceService.class), mock(ExecutionQueryService.class));
		ExtendedModelMap model = new ExtendedModelMap();

		controller.organization(model);

		Assertions.assertThat(model.get("executeConfirmation").toString()).contains("preview").doesNotContain("{0}");
	}

	/**
	 * The point of the whole change: a destructive action that says how much it is
	 * about to move. "Move 9600 files, 45.7 GB?" is a different decision from
	 * "organize now?", and the difference is the reason somebody stops to think.
	 */
	@Test
	void theExecuteConfirmationCountsTheFilesAndTheirWeightOncePlanned() {
		OrganizationService organizationService = mock(OrganizationService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), mock(ExecutionQueryService.class));
		UUID executionId = UUID.randomUUID();

		StoredPlanPage plan = new StoredPlanPage("C:/media/input", "C:/media/output", OrganizationLayout.DEFAULT,
				new OrganizationSummary(9600, 9600, 0, 0, 9600, 49_100_000_000L, 0, 0, 0), false, List.of(), 0, 50,
				9600);

		when(organizationService.planPagePublic(executionId, 0, 50, false)).thenReturn(Optional.of(plan));
		ExtendedModelMap model = new ExtendedModelMap();

		controller.previewResult(executionId, 0, 50, false, null, model);

		String confirmation = model.get("executeConfirmation").toString();

		Assertions.assertThat(confirmation).contains(SizeFormatter.format(49_100_000_000L)).contains("9")
				.doesNotContain("{0}").doesNotContain("{1}");
	}
}