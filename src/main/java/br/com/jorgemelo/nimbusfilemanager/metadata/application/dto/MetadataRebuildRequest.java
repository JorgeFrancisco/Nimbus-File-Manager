package br.com.jorgemelo.nimbusfilemanager.metadata.application.dto;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Month;
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
		@Schema(description = "Simulate rebuild without persisting changes.", example = "false") boolean dryRun,
		@Schema(description = "Only rebuild files not analysed since this instant, which is how a run continues where a previous one stopped.",
				example = "2026-07-26T11:16:13") LocalDateTime notAnalysedSince) {

	/**
	 * Real ceiling for {@link #limit}, independent of the caller-supplied value -
	 * see {@link OrganizationPreviewRequest#MAX_LIMIT}. Public because the settings
	 * panel states it on screen: a folder with more files than this needs the run
	 * repeated, and the screen has to say so from the single place that defines it.
	 */
	public static final int MAX_LIMIT = 250_000;

	/**
	 * Stands in for "no cutoff" when the caller wants every file. The query
	 * compares against it instead of testing the parameter for null because
	 * PostgreSQL cannot infer the type of a bare null timestamp parameter and
	 * rejects the statement - a far-future instant keeps one query, one code path,
	 * and every file a candidate.
	 */
	public static final LocalDateTime NO_CUTOFF = LocalDateTime.of(9999, Month.DECEMBER, 31, 23, 59, 59);

	/**
	 * Request for a whole folder, as the settings panel asks for it: no
	 * capture-date or date-source filter, and the highest limit the request
	 * honours, since a screen-driven rebuild is meant to cover the folder rather
	 * than a sample.
	 *
	 * <p>
	 * A folder larger than that ceiling takes more than one run, so
	 * {@code notAnalysedSince} carries the moment the previous run started: files
	 * it already rebuilt are stamped later than that and drop out of this one,
	 * which is what makes the next run continue instead of starting over.
	 */
	public static MetadataRebuildRequest forFolder(String sourcePath, List<MetadataRebuildField> refresh,
			boolean dryRun, LocalDateTime notAnalysedSince) {
		return new MetadataRebuildRequest(sourcePath, refresh, null, null, MAX_LIMIT, dryRun, notAnalysedSince);
	}

	/** The cutoff to query by: {@link #NO_CUTOFF} when none was asked for. */
	public LocalDateTime cutoff() {
		return notAnalysedSince == null ? NO_CUTOFF : notAnalysedSince;
	}

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