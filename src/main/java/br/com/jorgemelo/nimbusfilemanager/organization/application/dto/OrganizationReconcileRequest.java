package br.com.jorgemelo.nimbusfilemanager.organization.application.dto;

import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record OrganizationReconcileRequest(
		@Schema(description = "Folder to reconcile.",
				example = "C:/nimbus-file-manager/workspace/temp") @NotBlank String sourcePath,
		@Schema(description = "Scan subfolders recursively.", example = "true") Boolean recursive,
		@Schema(description = "Include hidden files and folders.", example = "false") Boolean includeHidden,
		@Schema(description = "Maximum sample items returned for each issue type.",
				example = "100") Integer sampleLimit) {

	private static final int DEFAULT_SAMPLE_LIMIT = 100;

	public Path source() {
		return PathUtils.normalizePath(sourcePath);
	}

	public boolean recursiveValue() {
		return recursive == null || recursive;
	}

	public boolean includeHiddenValue() {
		return includeHidden != null && includeHidden;
	}

	public int safeSampleLimit() {
		if (sampleLimit == null) {
			return DEFAULT_SAMPLE_LIMIT;
		}

		return Math.max(0, sampleLimit);
	}
}