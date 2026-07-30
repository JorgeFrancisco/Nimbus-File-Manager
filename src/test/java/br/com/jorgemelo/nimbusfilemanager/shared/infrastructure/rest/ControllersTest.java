package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.FingerprintFailureLabels;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.PhotoSimilarityService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.VideoSimilarityService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.PhashBacklogService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.VideoFingerprintBacklogService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.rest.DuplicateController;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.rest.ExecutionController;
import br.com.jorgemelo.nimbusfilemanager.media.application.MediaSearchService;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.MediaSearchCriteria;
import br.com.jorgemelo.nimbusfilemanager.media.infrastructure.rest.MediaController;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MetadataRebuildService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.rest.MetadataController;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationPreviewExportService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPlan;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPreviewRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationSummary;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationUndoResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.organization.infrastructure.rest.OrganizationController;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web.HomeController;
import br.com.jorgemelo.nimbusfilemanager.statistics.application.StatisticsService;
import br.com.jorgemelo.nimbusfilemanager.statistics.infrastructure.rest.StatisticsController;

class ControllersTest {

	@Test
	void organizationPreviewShouldDelegateAndRejectTooLargeInlinePreview() {
		OrganizationService service = mock(OrganizationService.class);
		OrganizationPreviewRequest request = previewRequest(100);
		OrganizationPlan plan = plan();
		OrganizationController controller = new OrganizationController(service,
				mock(OrganizationPreviewExportService.class));

		when(service.preview(request)).thenReturn(plan);

		Assertions.assertThat(controller.preview(request)).isSameAs(plan);

		OrganizationPreviewRequest invalidRequest = previewRequest(10001);

		Assertions.assertThatThrownBy(() -> controller.preview(invalidRequest))
				.isInstanceOf(ResponseStatusException.class).extracting("statusCode").isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void organizationExportShouldStreamZipWithPreviewJsonAndExecuteShouldDelegate() throws Exception {
		OrganizationService service = mock(OrganizationService.class);
		OrganizationPreviewRequest previewRequest = previewRequest(100);
		OrganizationExecuteRequest executeRequest = executeRequest();
		OrganizationExecuteResponse executeResponse = new OrganizationExecuteResponse(1L, "FINISHED",
				LocalDateTime.now(), LocalDateTime.now(), "C:/input", "C:/target", 1, 1, 0, 0, false, "ok");
		OrganizationUndoResponse undoResponse = new OrganizationUndoResponse(1L, "FINISHED", 1, 1, 0, 0, "ok",
				List.of());
		OrganizationPreviewExportService exportService = new OrganizationPreviewExportService(service,
				new ObjectMapper(), Clock.systemDefaultZone());
		OrganizationController controller = new OrganizationController(service, exportService);
		UUID executionId = UUID.randomUUID();

		when(service.preview(previewRequest)).thenReturn(plan());
		when(service.execute(executeRequest)).thenReturn(executeResponse);
		when(service.undoPublic(executionId)).thenReturn(undoResponse);

		var response = controller.exportPreview(previewRequest);

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
		MetadataRebuildService metadataRebuildService = mock(MetadataRebuildService.class);
		MediaSearchService mediaSearchService = mock(MediaSearchService.class);
		DuplicateService duplicateService = mock(DuplicateService.class);
		PhotoSimilarityService photoSimilarityService = mock(PhotoSimilarityService.class);
		PhashBacklogService phashBacklogService = mock(PhashBacklogService.class);
		VideoSimilarityService videoSimilarityService = mock(VideoSimilarityService.class);
		VideoFingerprintBacklogService videoFingerprintBacklogService = mock(VideoFingerprintBacklogService.class);
		ExecutionQueryService executionQueryService = mock(ExecutionQueryService.class);
		StatisticsService statisticsService = mock(StatisticsService.class);
		MetadataRebuildRequest metadataRequest = new MetadataRebuildRequest("C:/input", null, null, null, 100, false,
				null);
		PageRequest pageable = PageRequest.of(0, 10);

		when(mediaSearchService.search(any(), any())).thenReturn(new PageImpl<>(List.of()));
		when(duplicateService.groups(pageable, null)).thenReturn(new PageImpl<>(List.of()));
		when(duplicateService.candidates(pageable, null)).thenReturn(new PageImpl<>(List.of()));
		when(photoSimilarityService.groups(70, pageable)).thenReturn(new PageImpl<>(List.of()));
		when(videoSimilarityService.groups(70, pageable)).thenReturn(new PageImpl<>(List.of()));
		when(statisticsService.errorFileDetails(ExecutionErrorType.UNKNOWN, "path", pageable))
				.thenReturn(new PageImpl<>(List.of()));

		new MetadataController(metadataRebuildService).rebuild(metadataRequest);
		new MediaController(mediaSearchService).search(
				new MediaSearchCriteria(FileType.PHOTO, "h264", "folder", "jpg", 2024, 5, 1L, 10L), pageable);

		DuplicateController duplicateController = new DuplicateController(duplicateService, photoSimilarityService,
				phashBacklogService, videoSimilarityService, videoFingerprintBacklogService,
				new FingerprintFailureLabels());

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
		statisticsController.folders(20, FileType.VIDEO, "h265", "size");
		statisticsController.errors();
		statisticsController.errorFiles();
		statisticsController.errorFileDetails(ExecutionErrorType.UNKNOWN, "path", pageable);

		verify(metadataRebuildService).rebuild(metadataRequest);
		verify(mediaSearchService).search(any(), any());
		verify(duplicateService).files("hash");
		verify(phashBacklogService).failures();
		verify(executionQueryService).errorSummary(executionId);
		verify(executionQueryService).movements(executionId);
		verify(statisticsService).errorFileDetails(ExecutionErrorType.UNKNOWN, "path", pageable);
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

	private OrganizationPlan plan() {
		return new OrganizationPlan("C:/input", "C:/target", OrganizationLayout.DEFAULT, false,
				new OrganizationSummary(0, 0, 0, 0, 0, 0, 0, 0, 0), List.of());
	}
}