package br.com.jorgemelo.nimbusfilemanager.media.infrastructure.rest;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.jorgemelo.nimbusfilemanager.media.application.dto.ExplorerActionResult;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.ExplorerItemProperties;
import br.com.jorgemelo.nimbusfilemanager.media.application.explorer.ExplorerDeletionService;
import br.com.jorgemelo.nimbusfilemanager.media.application.explorer.ExplorerPropertiesService;
import br.com.jorgemelo.nimbusfilemanager.media.application.explorer.ExplorerRenameService;
import br.com.jorgemelo.nimbusfilemanager.media.domain.enums.ExplorerDeleteMode;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import io.swagger.v3.oas.annotations.Operation;

/**
 * Data API behind the card menu of the file explorer: properties, delete (to
 * quarantine or for good) and rename. It answers JSON only, so it is a
 * {@code @RestController} living with the other REST adapters, while the screen
 * that renders the explorer stays in {@code infrastructure/web}.
 *
 * <p>
 * Restricted to ADMIN in {@code SecurityConfig} alongside the other
 * operational APIs - these three write to the user's own disk. A refused action
 * answers {@code success=false} carrying the reason already localized: the
 * screen shows the sentence it is handed and never composes one.
 */
@RestController
@RequestMapping("/api/files")
public class FileExplorerActionsController {

	private final ExplorerPropertiesService propertiesService;
	private final ExplorerDeletionService deletionService;
	private final ExplorerRenameService renameService;

	public FileExplorerActionsController(ExplorerPropertiesService propertiesService,
			ExplorerDeletionService deletionService, ExplorerRenameService renameService) {
		this.propertiesService = propertiesService;
		this.deletionService = deletionService;
		this.renameService = renameService;
	}

	@GetMapping("/properties")
	@Operation(summary = "Returns the properties dialog data for a file or folder")
	public ResponseEntity<ExplorerItemProperties> properties(@RequestParam String path) throws IOException {
		return ResponseEntity.ok(propertiesService.of(target(path)));
	}

	@PostMapping("/delete")
	@Operation(summary = "Sends an entry to quarantine or deletes it permanently")
	public ExplorerActionResult delete(@RequestParam String path, @RequestParam ExplorerDeleteMode mode) {
		return mode == ExplorerDeleteMode.PERMANENT ? deletionService.deletePermanently(target(path))
				: deletionService.quarantine(target(path));
	}

	@PostMapping("/rename")
	@Operation(summary = "Renames a file or folder")
	public ExplorerActionResult rename(@RequestParam String path, @RequestParam String newName) {
		return renameService.rename(target(path), newName);
	}

	private Path target(String path) {
		return PathUtils.normalizePath(path);
	}
}