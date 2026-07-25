package br.com.jorgemelo.nimbusfilemanager.inventory.application.dto;

import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record InventoryRequest(
		@Schema(description = "Folder to scan.",
				example = "C:/nimbus-file-manager/workspace/temp") @NotBlank String sourcePath,
		@Schema(description = "Scan subfolders recursively.", example = "true") boolean recursive,
		@Schema(description = "Include hidden files and folders.", example = "false") boolean includeHidden,
		@Schema(description = "Calculate SHA-256 and MD5 hashes.", example = "true") boolean calculateHashes,
		@Schema(description = "Ignore cache and force metadata analysis.", example = "true") boolean forceAnalysis) {

	public Path source() {
		return PathUtils.normalizePath(sourcePath);
	}
}