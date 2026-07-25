package br.com.jorgemelo.nimbusfilemanager.catalog.infrastructure.rest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.CatalogExportService;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.CatalogExport;
import br.com.jorgemelo.nimbusfilemanager.catalog.domain.enums.CatalogExportFormat;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController extends LocalizedComponent {

	private final CatalogExportService catalogExportService;

	public CatalogController(CatalogExportService catalogExportService) {
		this.catalogExportService = catalogExportService;
	}

	@GetMapping("/export")
	@Operation(summary = "Exports the whole media catalog as a downloadable file",
			description = "Streams every catalog_file with its location as CSV (default) or JSON. Read-only: it never touches files or the catalog.")
	public ResponseEntity<StreamingResponseBody> export(
			@RequestParam(required = false, defaultValue = "csv") String format) {
		CatalogExport export = catalogExportService.export(resolveFormat(format));

		return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + export.fileName())
				.contentType(MediaType.parseMediaType(export.contentType())).body(export.body());
	}

	private CatalogExportFormat resolveFormat(String format) {
		try {
			return CatalogExportFormat.fromParam(format);
		} catch (IllegalArgumentException _) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message("backend.catalog.invalidFormat"));
		}
	}
}