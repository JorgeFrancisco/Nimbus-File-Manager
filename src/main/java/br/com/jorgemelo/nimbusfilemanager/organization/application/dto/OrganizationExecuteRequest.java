package br.com.jorgemelo.nimbusfilemanager.organization.application.dto;

import java.nio.file.Path;
import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationFallbackMode;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationSubdivision;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.MoveResult;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.util.NumberUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record OrganizationExecuteRequest(
		@Schema(description = "Folder used as organization source.",
				example = "C:/nimbus-file-manager/workspace/temp") @NotBlank String sourcePath,
		@Schema(description = "Folder used as organization target.",
				example = "C:/nimbus-file-manager/workspace/organized") @NotBlank String targetPath,
		@Schema(description = "Scan source subfolders recursively.", example = "true") Boolean recursive,
		@Schema(description = "Organization folder layout.", example = "DEFAULT") OrganizationLayout layout,
		@Schema(description = "Maximum number of items to process.", example = "10000") Integer limit,
		@Schema(description = "Rebuild metadata before planning.", example = "false") Boolean rebuildMetadata,
		@Schema(description = "Metadata fields rebuilt when rebuildMetadata is true.",
				example = "[\"DATE\", \"MIME\", \"GPS\"]") List<MetadataRebuildField> rebuild,
		@Schema(description = "Ignore files that are already in the expected organized location.",
				example = "true") Boolean skipAlreadyOrganized,
		@Schema(description = "Optional category filter.", example = "[\"MEDIA\"]") List<FileCategory> onlyCategories,
		@Schema(description = "Optional subcategory filter.",
				example = "[\"CAMERA\"]") List<MediaSubcategory> onlySubcategories,
		@Schema(description = "Optional extension filter without dots.",
				example = "[\"jpg\", \"mp4\"]") List<String> onlyExtensions,
		@Schema(description = "Optional file type filter.",
				example = "[\"PHOTO\", \"VIDEO\"]") List<FileType> onlyFileTypes,
		@Schema(description = "When false, any conflict rejects the whole execution before moving files.",
				example = "false") Boolean allowConflicts,
		@Schema(description = "Allow replacing an existing target file.", example = "false") Boolean overwriteExisting,
		@Schema(description = "Geographic subdivision inserted under the layout (default NONE).",
				example = "COUNTRY_STATE_CITY") LocationSubdivision locationSubdivision,
		@Schema(description = "Minimum confidence for the geographic subdivision; null accepts any confidence.",
				example = "HIGH") LocationConfidence locationMinConfidence,
		@Schema(description = "What to do when location is missing or below the minimum confidence (default IGNORE).",
				example = "IGNORE") LocationFallbackMode locationFallback,
		@Schema(description = "Dry-run: run the full execute flow as a simulation, blocked from touching disk or database. Used by preview.",
				example = "false") Boolean dryRun) {

	/**
	 * Real ceiling for {@link #limit}, independent of the caller-supplied value -
	 * see {@link OrganizationPreviewRequest#MAX_LIMIT}.
	 */
	private static final int MAX_LIMIT = 100_000;

	/** Backward-compatible constructor for callers without geographic options. */
	public OrganizationExecuteRequest(String sourcePath, String targetPath, Boolean recursive,
			OrganizationLayout layout, Integer limit, Boolean rebuildMetadata, List<MetadataRebuildField> rebuild,
			Boolean skipAlreadyOrganized, List<FileCategory> onlyCategories, List<MediaSubcategory> onlySubcategories,
			List<String> onlyExtensions, List<FileType> onlyFileTypes, Boolean allowConflicts,
			Boolean overwriteExisting) {
		this(sourcePath, targetPath, recursive, layout, limit, rebuildMetadata, rebuild, skipAlreadyOrganized,
				onlyCategories, onlySubcategories, onlyExtensions, onlyFileTypes, allowConflicts, overwriteExisting,
				LocationSubdivision.NONE, null, LocationFallbackMode.IGNORE);
	}

	/**
	 * Geographic-aware constructor without dry-run (defaults to a real execute).
	 */
	public OrganizationExecuteRequest(String sourcePath, String targetPath, Boolean recursive,
			OrganizationLayout layout, Integer limit, Boolean rebuildMetadata, List<MetadataRebuildField> rebuild,
			Boolean skipAlreadyOrganized, List<FileCategory> onlyCategories, List<MediaSubcategory> onlySubcategories,
			List<String> onlyExtensions, List<FileType> onlyFileTypes, Boolean allowConflicts,
			Boolean overwriteExisting, LocationSubdivision locationSubdivision,
			LocationConfidence locationMinConfidence, LocationFallbackMode locationFallback) {
		this(sourcePath, targetPath, recursive, layout, limit, rebuildMetadata, rebuild, skipAlreadyOrganized,
				onlyCategories, onlySubcategories, onlyExtensions, onlyFileTypes, allowConflicts, overwriteExisting,
				locationSubdivision, locationMinConfidence, locationFallback, false);
	}

	public Path source() {
		return PathUtils.normalizePath(sourcePath);
	}

	public Path target() {
		return PathUtils.normalizePath(targetPath);
	}

	public boolean recursiveValue() {
		return recursive == null || recursive;
	}

	public OrganizationLayout layoutValue() {
		return layout == null ? OrganizationLayout.DEFAULT : layout;
	}

	public int safeLimit() {
		return NumberUtils.limit(limit, 10000, MAX_LIMIT);
	}

	public boolean rebuildMetadataValue() {
		return rebuildMetadata != null && rebuildMetadata;
	}

	public boolean skipAlreadyOrganizedValue() {
		return skipAlreadyOrganized == null || skipAlreadyOrganized;
	}

	public boolean allowConflictsValue() {
		return allowConflicts != null && allowConflicts;
	}

	public boolean overwriteExistingValue() {
		return overwriteExisting != null && overwriteExisting;
	}

	/**
	 * When true, the execute flow runs as a dry-run: every side effect (physical
	 * move, catalog persistence, movement recording) is blocked, but all read-only
	 * checks run, so the simulated {@link MoveResult} of each item matches exactly
	 * what a real execute would produce. This is how preview is a true simulator of
	 * execute.
	 */
	public boolean dryRunValue() {
		return dryRun != null && dryRun;
	}

	public OrganizationPreviewRequest toPreviewRequest() {
		return new OrganizationPreviewRequest(sourcePath, targetPath, recursive, layout, limit, rebuildMetadata,
				rebuild, skipAlreadyOrganized, onlyCategories, onlySubcategories, onlyExtensions, onlyFileTypes,
				locationSubdivision, locationMinConfidence, locationFallback);
	}
}