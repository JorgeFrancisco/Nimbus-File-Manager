package br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.MetadataRebuildLauncher;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/metadata")
public class MetadataController {

	private final MetadataRebuildLauncher metadataRebuildLauncher;

	public MetadataController(MetadataRebuildLauncher metadataRebuildLauncher) {
		this.metadataRebuildLauncher = metadataRebuildLauncher;
	}

	/**
	 * Queues the rebuild and answers with the execution to follow.
	 *
	 * <p>
	 * It used to run inside the request and return what it had done. That answer
	 * was honest only for a small folder, and it came from the application reading
	 * files with exiftool while a worker existed to do exactly that - and with no
	 * guard at all against the pass the settings screen might have started a second
	 * earlier. 202 with the row is the same answer the similarity endpoints give,
	 * and the row is where the counters and the outcome are.
	 */
	@PostMapping("/rebuild")
	@Operation(summary = "Queues a rebuild of selected metadata fields for files already registered in the inventory",
			description = "Writes the request and returns 202 with the execution to follow. When refresh is omitted or empty, DATE is rebuilt by default; dryRun asks what the pass would change instead of changing it.")
	public ResponseEntity<Void> rebuild(@RequestBody @Valid MetadataRebuildRequest request) {
		return metadataRebuildLauncher
				.launch(request.sourcePath(), request.refresh(), request.dryRun(), request.notAnalysedSince())
				.map(this::accepted).orElseGet(() -> ResponseEntity.status(503).build());
	}

	private ResponseEntity<Void> accepted(Execution execution) {
		return ResponseEntity.accepted().header("Location", "/api/executions/" + execution.getPublicId()).build();
	}
}