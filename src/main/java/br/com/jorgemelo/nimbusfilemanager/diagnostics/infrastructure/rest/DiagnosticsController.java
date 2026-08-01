package br.com.jorgemelo.nimbusfilemanager.diagnostics.infrastructure.rest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import br.com.jorgemelo.nimbusfilemanager.diagnostics.application.DiagnosticsBundleService;
import br.com.jorgemelo.nimbusfilemanager.diagnostics.application.dto.DiagnosticsBundle;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/diagnostics")
@Tag(name = "Diagnostics", description = "Support bundle of one installation")
public class DiagnosticsController {

	private final DiagnosticsBundleService diagnosticsBundleService;

	public DiagnosticsController(DiagnosticsBundleService diagnosticsBundleService) {
		this.diagnosticsBundleService = diagnosticsBundleService;
	}

	@GetMapping("/export")
	@Operation(summary = "Downloads a diagnostics archive of this installation",
			description = "Bundles the installation summary, the stored settings with secrets masked, the recent executions and the tail of the log. Read-only: it never touches files or the catalog.")
	public ResponseEntity<StreamingResponseBody> export() {
		DiagnosticsBundle bundle = diagnosticsBundleService.export();

		return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + bundle.fileName())
				.contentType(MediaType.parseMediaType(bundle.contentType())).body(bundle.body());
	}
}