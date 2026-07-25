package br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.MetadataRebuildService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/metadata")
public class MetadataController {

	private final MetadataRebuildService metadataRebuildService;

	public MetadataController(MetadataRebuildService metadataRebuildService) {
		this.metadataRebuildService = metadataRebuildService;
	}

	@PostMapping("/rebuild")
	@Operation(summary = "Rebuilds selected metadata fields for files already registered in the inventory",
			description = "Updates metadata for files already known by the inventory. When refresh is omitted or empty, DATE is rebuilt by default.")
	public MetadataRebuildResponse rebuild(@RequestBody @Valid MetadataRebuildRequest request) {
		return metadataRebuildService.rebuild(request);
	}
}