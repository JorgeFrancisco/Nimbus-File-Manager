package br.com.jorgemelo.nimbusfilemanager.organization.infrastructure.rest;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationPreviewExportService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPreviewExport;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPreviewRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.StoredPlanPage;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/organization")
public class OrganizationController extends LocalizedComponent {

	private static final int MAX_PAGE_SIZE = 500;

	private final OrganizationService organizationService;
	private final OrganizationPreviewExportService organizationPreviewExportService;

	public OrganizationController(OrganizationService organizationService,
			OrganizationPreviewExportService organizationPreviewExportService) {
		this.organizationService = organizationService;
		this.organizationPreviewExportService = organizationPreviewExportService;
	}

	/**
	 * Asks for a plan and answers with the run that will produce it.
	 *
	 * <p>
	 * It used to calculate one inside the request and return it in the body, capped
	 * at ten thousand items so the wait stayed bearable. Nothing calculates a plan
	 * in a request any more - a worker does, once, and both this API and the screen
	 * read the same published rows. The cap goes with it: the plan is paginated now,
	 * so its size stopped being a reason to refuse.
	 */
	@PostMapping("/preview")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Operation(summary = "Queues the building of an organization plan",
			description = "Validates the folders and queues the preview; a worker builds the plan and publishes it."
					+ " Poll the returned execution, then read the plan from /preview/{executionId}."
					+ " No file is moved. The target path cannot be the same as the source path or inside it.")
	public ExecutionResponse preview(@RequestBody @Valid OrganizationPreviewRequest request) {
		return organizationService.previewAsync(request.toExecuteRequest());
	}

	/**
	 * The published plan, one page at a time.
	 *
	 * <p>
	 * A plan that was never built, one still being built, one that failed and one
	 * past its expiry are all {@code 404}: they are the same answer to a consumer -
	 * there is nothing here - and telling them apart would offer a distinction
	 * nobody can act on.
	 */
	@GetMapping("/preview/{executionId}")
	@Operation(summary = "Returns a page of a published organization plan",
			description = "Reads the plan a worker published for the given preview execution. Answers 404 while it is"
					+ " still being built, and after it expires.")
	public StoredPlanPage previewPlan(@PathVariable UUID executionId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
			@RequestParam(defaultValue = "false") boolean onlyConflicts) {
		return organizationService.planPagePublic(executionId, page, safeSize(size), onlyConflicts)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						message("backend.organization.previewNotFound", executionId)));
	}

	/**
	 * The published plan as a downloadable file.
	 *
	 * <p>
	 * It used to rebuild the whole plan inside the request to serialize it. It
	 * streams what was published instead, so an export describes exactly the plan
	 * the user was looking at rather than a second one computed at download time.
	 */
	@GetMapping("/preview/{executionId}/export")
	@Operation(summary = "Exports a published organization plan as a ZIP file",
			description = "Streams the published plan as a ZIP containing its JSON. It reads what a worker published"
					+ " rather than recalculating, so the file describes the same plan the screen showed.")
	public ResponseEntity<StreamingResponseBody> exportPreview(@PathVariable UUID executionId) {
		OrganizationPreviewExport export = organizationPreviewExportService.export(executionId);

		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + export.zipFileName())
				.contentType(MediaType.parseMediaType("application/zip")).body(export.body());
	}

	/**
	 * Answers with the queued execution rather than the finished run. It is not a
	 * choice of style: the moving happens in the worker process now, so there is no
	 * thread here that could wait for counts to report. Keeping one would have
	 * meant keeping a second copy of the executor in this process, which is exactly
	 * the arrangement being undone.
	 */
	@PostMapping("/execute")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Operation(summary = "Queues an organization run and returns its execution",
			description = "Validates the folders and queues the run; a worker recalculates the plan, validates conflicts and physically moves the files. Poll the returned execution for progress and the final counts.")
	public ExecutionResponse execute(@RequestBody @Valid OrganizationExecuteRequest request) {
		return organizationService.executeAsync(request);
	}

	@PostMapping("/execute/{executionId}/undo")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Operation(summary = "Queues the reversal of an organization execution",
			description = "Queues a run that moves files from the organization target paths back to their original paths, using the movement records of the given executionId. Poll the returned execution for the final counts.")
	public ExecutionResponse undo(@PathVariable UUID executionId) {
		return organizationService.undoPublic(executionId);
	}
	private int safeSize(int size) {
		return Math.clamp(size, 1, MAX_PAGE_SIZE);
	}
}