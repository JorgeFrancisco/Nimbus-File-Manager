package br.com.jorgemelo.nimbusfilemanager.organization.infrastructure.rest;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationPreviewExportService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPlan;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPreviewExport;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPreviewRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationUndoResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/organization")
public class OrganizationController extends LocalizedComponent {

	private static final int MAX_INLINE_PREVIEW_LIMIT = 10_000;

	private final OrganizationService organizationService;
	private final OrganizationPreviewExportService organizationPreviewExportService;

	public OrganizationController(OrganizationService organizationService,
			OrganizationPreviewExportService organizationPreviewExportService) {
		this.organizationService = organizationService;
		this.organizationPreviewExportService = organizationPreviewExportService;
	}

	@PostMapping("/preview")
	@Operation(summary = "Builds a limited organization plan for interactive preview",
			description = "Calculates target paths and conflicts without persisting a plan and without moving files. The target path cannot be the same as the source path or inside it.")
	public OrganizationPlan preview(@RequestBody @Valid OrganizationPreviewRequest request) {
		if (request.safeLimit() > MAX_INLINE_PREVIEW_LIMIT) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					message("backend.organization.previewLimitExceeded", MAX_INLINE_PREVIEW_LIMIT));
		}

		return organizationService.preview(request);
	}

	@PostMapping("/preview/export")
	@Operation(summary = "Exports an organization plan as a ZIP file",
			description = "Builds the complete organization plan and streams it as a downloadable ZIP file containing the JSON preview. This endpoint does not move files.")
	public ResponseEntity<StreamingResponseBody> exportPreview(@RequestBody @Valid OrganizationPreviewRequest request) {
		OrganizationPreviewExport export = organizationPreviewExportService.export(request);

		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + export.zipFileName())
				.contentType(MediaType.parseMediaType("application/zip")).body(export.body());
	}

	@PostMapping("/execute")
	@Operation(summary = "Executes an organization plan and moves the files on disk",
			description = "Recalculates the organization plan internally, validates conflicts and physically moves files. There is no previewId, dryRun or copy mode in the current contract.")
	public OrganizationExecuteResponse execute(@RequestBody @Valid OrganizationExecuteRequest request) {
		return organizationService.execute(request);
	}

	@PostMapping("/execute/{executionId}/undo")
	@Operation(summary = "Undoes an organization execution",
			description = "Moves files from the organization target paths back to their original paths using movement records from the given executionId. Already undone movements are skipped.")
	public OrganizationUndoResponse undo(@PathVariable UUID executionId) {
		return organizationService.undoPublic(executionId);
	}
}