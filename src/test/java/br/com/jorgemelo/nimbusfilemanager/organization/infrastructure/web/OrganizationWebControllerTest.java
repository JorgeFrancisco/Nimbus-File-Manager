package br.com.jorgemelo.nimbusfilemanager.organization.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPlan;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationSummary;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;

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

		Assertions.assertThat(model).containsEntry("sourcePath", "D:/SEPARAR/ORGANIZADOS")
				.containsEntry("targetPath", "D:/SEPARAR/ORGANIZADOS 2");
	}

	@Test
	void organizationPreviewResultShouldRenderStoredPlanOrErrorWhenMissing() {
		OrganizationService organizationService = mock(OrganizationService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), mock(ExecutionQueryService.class));
		ExtendedModelMap foundModel = new ExtendedModelMap();
		ExtendedModelMap missingModel = new ExtendedModelMap();
		OrganizationPlan plan = plan();

		when(organizationService.getPreviewPlan(1L)).thenReturn(plan);
		when(organizationService.getPreviewPlan(2L)).thenReturn(null);

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
		when(organizationService.getPreviewPlanPublic(executionId)).thenReturn(null);
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
		when(organizationService.getPreviewPlanPublic(executionId)).thenReturn(null);
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
		Assertions.assertThat(previewErrorFor("PROCESSING_FILES")).contains("ainda está sendo processada");
		Assertions.assertThat(previewErrorFor("FINISHED_WITH_ERRORS")).contains("terminou com erro");
	}

	private String previewErrorFor(String status) {
		OrganizationService organizationService = mock(OrganizationService.class);
		ExecutionQueryService executionQueryService = mock(ExecutionQueryService.class);

		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), executionQueryService);

		UUID executionId = UUID.randomUUID();

		when(organizationService.getPreviewPlanPublic(executionId)).thenReturn(null);
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

		when(organizationService.getPreviewPlan(1L)).thenReturn(plan());
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

	@Test
	void organizationPreviewResultShouldPaginateNonEmptyPlan() {
		OrganizationService organizationService = mock(OrganizationService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), mock(ExecutionQueryService.class));
		OrganizationPlan plan = planWithItems(25);
		ExtendedModelMap firstPageModel = new ExtendedModelMap();
		ExtendedModelMap lastPageModel = new ExtendedModelMap();

		when(organizationService.getPreviewPlan(1L)).thenReturn(plan);

		controller.previewResult(1L, 0, 20, firstPageModel);
		controller.previewResult(1L, 1, 20, lastPageModel);

		Assertions.assertThat(firstPageModel).containsEntry("totalPages", 2).containsEntry("hasPrevious", false)
				.containsEntry("hasNext", true);
		Assertions.assertThat(lastPageModel).containsEntry("hasPrevious", true).containsEntry("hasNext", false);
	}

	@Test
	void previewResultWithOnlyConflictsShouldPaginateJustTheConflictedItems() {
		OrganizationService organizationService = mock(OrganizationService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), mock(ExecutionQueryService.class));
		ExtendedModelMap model = new ExtendedModelMap();

		List<OrganizationItem> items = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			boolean conflict = i % 2 == 0; // items 0, 2, 4 conflict -> 3 total
			items.add(new OrganizationItem((long) i, "file" + i + ".jpg", "C:/in/file" + i + ".jpg",
					"C:/out/file" + i + ".jpg", "202405", "09", "MEDIA", "CAMERA", "IMAGENS", "CAMERA", null, 100L,
					false, false, conflict, false, conflict, conflict ? "TARGET_EXISTS" : null));
		}
		OrganizationPlan plan = new OrganizationPlan("C:/in", "C:/out", OrganizationLayout.DEFAULT, false,
				new OrganizationSummary(5, 5, 0, 0, 5, 0, 3, 3, 0), items);

		UUID executionId = UUID.randomUUID();
		when(organizationService.getPreviewPlanPublic(executionId)).thenReturn(plan);

		controller.previewResult(executionId, 0, 20, true, null, model);

		Assertions.assertThat(model).containsEntry("onlyConflicts", true).containsEntry("totalItems", 3);
		Assertions.assertThat((List<?>) model.get("previewItems")).hasSize(3);
	}

	@Test
	void previewResultShouldExposeLocalizedConflictTypeLabelsByEnumName() {
		OrganizationService organizationService = mock(OrganizationService.class);
		OrganizationWebController controller = new OrganizationWebController(organizationService,
				mock(UserPagePreferenceService.class), mock(ExecutionQueryService.class));
		ExtendedModelMap model = new ExtendedModelMap();

		when(organizationService.getPreviewPlan(1L)).thenReturn(plan());

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

	private OrganizationPlan plan() {
		return new OrganizationPlan("C:/media/input", "C:/media/output", OrganizationLayout.DEFAULT, false,
				new OrganizationSummary(0, 0, 0, 0, 0, 0, 0, 0, 0), List.of());
	}

	private static OrganizationForm orgForm(String sourcePath, String targetPath, boolean recursive,
			OrganizationLayout layout, Integer limit, Integer page, Integer size) {
		return new OrganizationForm(sourcePath, targetPath, recursive, layout, limit, false, false, page, size, null,
				null, null);
	}

	private OrganizationPlan planWithItems(int count) {
		List<OrganizationItem> items = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			items.add(new OrganizationItem((long) i, "file" + i + ".jpg", "C:/media/input/file" + i + ".jpg",
					"C:/media/output/file" + i + ".jpg", "202405", "09", "MEDIA", "CAMERA", "IMAGENS", "CAMERA", null,
					100L, false, false, false, false, false, null));
		}

		return new OrganizationPlan("C:/media/input", "C:/media/output", OrganizationLayout.DEFAULT, false,
				new OrganizationSummary(count, count, 0, count, 0, 0, 0, 0, 0), items);
	}

	private ExecutionResponse execution() {
		return new ExecutionResponse(1L, "INVENTORY", "FINISHED", NOW, NOW, "C:/media/input", null, 1, 1, 0, 0, 0, 0,
				null, null, "ok", false);
	}
}