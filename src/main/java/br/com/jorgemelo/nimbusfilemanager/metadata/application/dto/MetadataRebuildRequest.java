package br.com.jorgemelo.nimbusfilemanager.metadata.application.dto;

import java.nio.file.Path;
import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.util.NumberUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record MetadataRebuildRequest(
		@Schema(description = "Folder scope for files already registered in the inventory.",
				example = "C:/nimbus-file-manager/workspace/temp") @NotBlank String sourcePath,
		@Schema(description = "Fields to rebuild. When omitted or empty, DATE is rebuilt.",
				example = "[\"DATE\", \"MIME\", \"GPS\", \"DIMENSIONS\", \"CAMERA\", \"SUBCATEGORY\"]") List<MetadataRebuildField> refresh,
		@Schema(description = "Only rebuild files with null capture date.", example = "false") Boolean captureDateNull,
		@Schema(description = "Only rebuild files with this current date source.",
				example = "FILE_NAME") DateSource dateSource,
		@Schema(description = "Maximum number of files to process.", example = "10000") Integer limit,
		@Schema(description = "Simulate rebuild without persisting changes.", example = "false") boolean dryRun) {

	/**
	 * Real ceiling for {@link #limit}, independent of the caller-supplied value -
	 * see {@link OrganizationPreviewRequest#MAX_LIMIT}.
	 */
	private static final int MAX_LIMIT = 100_000;

	public Path source() {
		return PathUtils.normalizePath(sourcePath);
	}

	public int safeLimit() {
		return NumberUtils.limit(limit, 10000, MAX_LIMIT);
	}

	public boolean shouldRefresh(MetadataRebuildField field) {
		if (refresh == null || refresh.isEmpty()) {
			return field == MetadataRebuildField.DATE;
		}

		return refresh.contains(MetadataRebuildField.ALL) || refresh.contains(field);
	}
}