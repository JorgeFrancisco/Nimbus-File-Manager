package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.EtaLabels;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.FingerprintFailureLabels;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.SimilarityLauncher;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.SimilarityViewService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityView;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintBacklogProgressReader;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintRunReader;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.PhashBacklogService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.VideoFingerprintBacklogService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.rest.DuplicateController;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.rest.ExecutionController;
import br.com.jorgemelo.nimbusfilemanager.media.application.MediaSearchService;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.MediaSearchCriteria;
import br.com.jorgemelo.nimbusfilemanager.media.infrastructure.rest.MediaController;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MetadataRebuildLauncher;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.rest.MetadataController;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationPreviewExportService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPreviewRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationSummary;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.StoredPlanPage;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.organization.infrastructure.rest.OrganizationController;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web.HomeController;
import br.com.jorgemelo.nimbusfilemanager.statistics.application.StatisticsService;
import br.com.jorgemelo.nimbusfilemanager.statistics.infrastructure.rest.StatisticsController;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerAvailability;
import br.com.jorgemelo.nimbusfilemanager.worker.application.dto.WorkerAvailabilityResponse;
import br.com.jorgemelo.nimbusfilemanager.worker.infrastructure.rest.WorkerHealthController;

class ControllersTest {

	/**
	 * The preview endpoint queues and answers with the run, instead of computing a
	 * plan inside the request and returning it in the body. The cap on how big an
	 * inline plan could be went with the body: the plan is paginated now, so its
	 * size stopped being a reason to refuse a request.
	 */
	@Test
	void organizationPreviewQueuesAndAnswersWithTheExecution() {
		OrganizationService service = mock(OrganizationService.class);
		OrganizationController controller = new OrganizationController(service,
				mock(OrganizationPreviewExportService.class));

		OrganizationPreviewRequest request = previewRequest(100);

		ExecutionResponse queued = new ExecutionResponse(UUID.randomUUID(), "ORGANIZATION_PREVIEW", "PENDING",
				LocalDateTime.now(), null, "C:/input", "C:/target", 0, 0, 0, 0, 0, 0, null, null, "queued", true);

		when(service.previewAsync(any())).thenReturn(queued);

		Assertions.assertThat(controller.preview(request)).isSameAs(queued);
		Assertions.assertThat(controller.preview(previewRequest(100_000))).isSameAs(queued);
	}

	@Test
	void organizationPlanIsReadFromWhatWasPublished() {
		OrganizationService service = mock(OrganizationService.class);
		OrganizationController controller = new OrganizationController(service,
				mock(OrganizationPreviewExportService.class));

		UUID executionId = UUID.randomUUID();

		StoredPlanPage page = page();

		when(service.planPagePublic(executionId, 0, 50, false)).thenReturn(Optional.of(page));

		Assertions.assertThat(controller.previewPlan(executionId, 0, 50, false)).isSameAs(page);
	}

	/** A plan that is not there - never built, still building, expired - is 404. */
	@Test
	void aPlanThatIsNotThereIsNotFound() {
		OrganizationService service = mock(OrganizationService.class);
		OrganizationController controller = new OrganizationController(service,
				mock(OrganizationPreviewExportService.class));

		UUID executionId = UUID.randomUUID();

		when(service.planPagePublic(executionId, 0, 50, false)).thenReturn(Optional.empty());

		Assertions.assertThatThrownBy(() -> controller.previewPlan(executionId, 0, 50, false))
				.isInstanceOf(ResponseStatusException.class).extracting("statusCode")
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void organizationExportStreamsThePublishedPlanAndExecuteDelegates() throws Exception {
		OrganizationService service = mock(OrganizationService.class);
		OrganizationExecuteRequest executeRequest = executeRequest();
		ExecutionResponse executeResponse = new ExecutionResponse(UUID.randomUUID(), "ORGANIZATION", "PENDING",
				LocalDateTime.now(), null, "C:/input", "C:/target", 0, 0, 0, 0, 0, 0, null, null, "queued", true);
		ExecutionResponse undoResponse = new ExecutionResponse(UUID.randomUUID(), "UNDO", "PENDING",
				LocalDateTime.now(), null, "C:/target", "C:/input", 0, 0, 0, 0, 0, 0, null, null, "queued", true);
		OrganizationPreviewExportService exportService = new OrganizationPreviewExportService(service,
				new ObjectMapper(), Clock.systemDefaultZone());
		OrganizationController controller = new OrganizationController(service, exportService);
		UUID executionId = UUID.randomUUID();

		when(service.planPagePublic(eq(executionId), anyInt(), anyInt(), anyBoolean()))
				.thenReturn(Optional.of(page()));
		when(service.executeAsync(executeRequest)).thenReturn(executeResponse);
		when(service.undoPublic(executionId)).thenReturn(undoResponse);

		var response = controller.exportPreview(executionId);

		ByteArrayOutputStream output = new ByteArrayOutputStream();

		response.getBody().writeTo(output);

		ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()));

		Assertions.assertThat(response.getHeaders().getFirst("Content-Disposition")).contains(".zip");
		Assertions.assertThat(zip.getNextEntry().getName()).startsWith("organization-preview-").endsWith(".json");
		Assertions.assertThat(controller.execute(executeRequest)).isSameAs(executeResponse);
		Assertions.assertThat(controller.undo(executionId)).isSameAs(undoResponse);
	}

	@Test
	void simpleControllersShouldDelegateToServices() {
		MetadataRebuildLauncher metadataRebuildLauncher = mock(MetadataRebuildLauncher.class);
		MediaSearchService mediaSearchService = mock(MediaSearchService.class);
		DuplicateService duplicateService = mock(DuplicateService.class);
		SimilarityViewService similarityViewService = mock(SimilarityViewService.class);
		SimilarityLauncher similarityLauncher = mock(SimilarityLauncher.class);
		PhashBacklogService phashBacklogService = mock(PhashBacklogService.class);
		VideoFingerprintBacklogService videoFingerprintBacklogService = mock(VideoFingerprintBacklogService.class);
		ExecutionQueryService executionQueryService = mock(ExecutionQueryService.class);
		StatisticsService statisticsService = mock(StatisticsService.class);
		MetadataRebuildRequest metadataRequest = new MetadataRebuildRequest("C:/input", null, null, null, 100, false,
				null);
		PageRequest pageable = PageRequest.of(0, 10);

		when(mediaSearchService.search(any(), any())).thenReturn(new PageImpl<>(List.of()));
		when(duplicateService.groups(pageable, null)).thenReturn(new PageImpl<>(List.of()));
		when(duplicateService.candidates(pageable, null)).thenReturn(new PageImpl<>(List.of()));
		when(similarityViewService.photos(70, pageable)).thenReturn(publishedView());
		when(similarityViewService.videos(70, pageable)).thenReturn(publishedView());
		when(statisticsService.errorFileDetails(ExecutionErrorType.UNKNOWN, "path", pageable))
				.thenReturn(new PageImpl<>(List.of()));

		new MetadataController(metadataRebuildLauncher).rebuild(metadataRequest);
		new MediaController(mediaSearchService)
				.search(new MediaSearchCriteria(FileType.PHOTO, "h264", "folder", "jpg", 2024, 5, 1L, 10L, null, null,
						null, null, null, null), pageable);

		DuplicateController duplicateController = new DuplicateController(duplicateService, similarityViewService,
				similarityLauncher, phashBacklogService, videoFingerprintBacklogService,
				new FingerprintFailureLabels(), new FingerprintBacklogProgressReader(mock(EtaLabels.class),
						phashBacklogService, videoFingerprintBacklogService, mock(FingerprintRunReader.class)));

		duplicateController.groups(pageable);
		duplicateController.files("hash");
		duplicateController.summary();
		duplicateController.candidates(pageable);
		duplicateController.similarPhotos(70, pageable);
		duplicateController.similarPhotoFailures();
		duplicateController.similarVideos(70, pageable);
		duplicateController.similarVideoFailures();

		ExecutionController executionController = new ExecutionController(executionQueryService);
		UUID executionId = UUID.randomUUID();

		executionController.list();
		executionController.get(executionId);
		executionController.steps(executionId);
		executionController.errors(executionId);
		executionController.errorSummary(executionId);
		executionController.movements(executionId);

		StatisticsController statisticsController = new StatisticsController(statisticsService);

		statisticsController.summary();
		statisticsController.codecs();
		statisticsController.extensions(50);
		statisticsController.folders(20, FileType.VIDEO, "h265", "size");
		statisticsController.errors();
		statisticsController.errorFiles();
		statisticsController.errorFileDetails(ExecutionErrorType.UNKNOWN, "path", pageable);

		verify(metadataRebuildLauncher).launch(metadataRequest.sourcePath(), metadataRequest.refresh(),
				metadataRequest.dryRun(), metadataRequest.notAnalysedSince());
		verify(mediaSearchService).search(any(), any());
		verify(duplicateService).files("hash");
		verify(phashBacklogService).failures();
		verify(executionQueryService).errorSummary(executionId);
		verify(executionQueryService).movements(executionId);
		verify(statisticsService).extensions(50);
		verify(statisticsService).errorFileDetails(ExecutionErrorType.UNKNOWN, "path", pageable);
	}

	/**
	 * Whether there is an executor is a fact the endpoint passes through - it does
	 * not decide anything about it, because what to do when the answer is no
	 * depends on what was queued.
	 */
	@Test
	void workerHealthShouldReportWhatAvailabilitySays() {
		WorkerAvailability workerAvailability = mock(WorkerAvailability.class);
		WorkerAvailabilityResponse absent = new WorkerAvailabilityResponse(false, 0, LocalDateTime.now());

		when(workerAvailability.current()).thenReturn(absent);

		Assertions.assertThat(new WorkerHealthController(workerAvailability).current()).isEqualTo(absent);
	}

	@Test
	void homeAndExceptionHandlerShouldReturnExpectedResponses() {
		RestExceptionHandler handler = new RestExceptionHandler();
		MethodArgumentNotValidException validationException = mock(MethodArgumentNotValidException.class);

		when(validationException.getMessage()).thenReturn("invalid field");

		Assertions.assertThat(new HomeController().home()).isEqualTo("redirect:/app");
		Assertions.assertThat(handler.badRequest(new IllegalArgumentException("bad")).getBody()).containsEntry("error",
				"bad");
		Assertions.assertThat(handler.validation(validationException).getBody()).containsEntry("error",
				"Requisição inválida.");
		Assertions.assertThat(handler.generic(new RuntimeException("boom")).getStatusCode())
				.isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	/**
	 * The generic handler must never echo {@code e.getMessage()} - a raw
	 * JDBC/filesystem exception message routinely leaks internal file paths or
	 * database details. Even though {@code /api/**} now requires a logged-in
	 * session (it was public before), not leaking internals to any API caller stays
	 * the policy: it should return a fixed, generic message plus a reference id the
	 * caller can quote to an admin, who correlates it back to the full exception in
	 * the server log.
	 */
	@Test
	void genericExceptionHandlerShouldNotLeakRawExceptionMessage() {
		RestExceptionHandler handler = new RestExceptionHandler();
		String sensitiveMessage = "FileNotFoundException: C:\\workspace\\backup\\secret\\file.jpg (Access is denied)";

		var response = handler.generic(new RuntimeException(sensitiveMessage));

		Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		Assertions.assertThat(response.getBody()).containsEntry("error", "Erro interno no servidor.");
		Assertions.assertThat(response.getBody().get("reference")).isNotNull().asString().isNotBlank()
				.doesNotContain("workspace", "backup", "secret");
		Assertions.assertThat(response.getBody().values())
				.noneMatch(value -> String.valueOf(value).contains(sensitiveMessage));
	}

	/**
	 * A parameter in the wrong shape is the caller's mistake: 400 with the generic
	 * message, never the 500-with-stack-trace the generic handler produces. A page
	 * that built {@code ?w=null} for every thumbnail once filled the log with
	 * identical stacks at ERROR, which is the level reserved for what actually
	 * needs investigating.
	 */
	@Test
	void aParameterOfTheWrongTypeShouldBeRejectedAsABadRequest() {
		RestExceptionHandler handler = new RestExceptionHandler();
		MethodArgumentTypeMismatchException mismatch = mock(MethodArgumentTypeMismatchException.class);

		when(mismatch.getName()).thenReturn("w");
		when(mismatch.getValue()).thenReturn("null");
		doReturn(int.class).when(mismatch).getRequiredType();

		var response = handler.typeMismatch(mismatch);

		Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		Assertions.assertThat(response.getBody()).containsEntry("error", "Requisição inválida.");
	}

	/**
	 * The required type is absent for a parameter Spring could not resolve a target
	 * type for; the log line still has to name the parameter instead of failing on
	 * a null.
	 */
	@Test
	void aTypeMismatchWithoutARequiredTypeShouldStillBeRejected() {
		RestExceptionHandler handler = new RestExceptionHandler();
		MethodArgumentTypeMismatchException mismatch = mock(MethodArgumentTypeMismatchException.class);

		when(mismatch.getName()).thenReturn("page");

		Assertions.assertThat(handler.typeMismatch(mismatch).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void clientDisconnectDuringMediaStreamingShouldNotProduceAJsonErrorResponse() {
		RestExceptionHandler handler = new RestExceptionHandler();

		Assertions.assertThatCode(
				() -> handler.clientDisconnected(new AsyncRequestNotUsableException("client closed image response")))
				.doesNotThrowAnyException();
	}

	private OrganizationPreviewRequest previewRequest(int limit) {
		return new OrganizationPreviewRequest("C:/input", "C:/target", true, OrganizationLayout.DEFAULT, limit, null,
				null, null, null, null, null, null);
	}

	private OrganizationExecuteRequest executeRequest() {
		return new OrganizationExecuteRequest("C:/input", "C:/target", true, OrganizationLayout.DEFAULT, 100, null,
				null, null, null, null, null, null, false, false);
	}

	private StoredPlanPage page() {
		return new StoredPlanPage("C:/input", "C:/target", OrganizationLayout.DEFAULT,
				new OrganizationSummary(0, 0, 0, 0, 0, 0, 0, 0, 0), false, List.of(), 0, 50, 0);
	}

	/** A published analysis with no groups: the endpoint answers 200, not 202. */
	private static SimilarityView publishedView() {
		return new SimilarityView(Page.empty(), true, false, 0, 0, 8000, true);
	}
}