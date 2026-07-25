package br.com.jorgemelo.nimbusfilemanager.organization.application.dto;

import java.nio.file.Path;
import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationFallbackMode;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationSubdivision;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.util.NumberUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record OrganizationPreviewRequest(
		@Schema(description = "Folder used as organization source.", example = "C:/nimbus-file-manager/workspace/temp") @NotBlank String sourcePath,
		@Schema(description = "Folder used as organization target.", example = "C:/nimbus-file-manager/workspace/organized") @NotBlank String targetPath,
		@Schema(description = "Scan source subfolders recursively.", example = "true") Boolean recursive,
		@Schema(description = "Organization folder layout.", example = "DEFAULT") OrganizationLayout layout,
		@Schema(description = "Maximum number of preview items returned inline.", example = "10000") Integer limit,
		@Schema(description = "Rebuild metadata before planning.", example = "false") Boolean rebuildMetadata,
		@Schema(description = "Metadata fields rebuilt when rebuildMetadata is true.", example = "[\"DATE\", \"MIME\", \"GPS\"]") List<MetadataRebuildField> rebuild,
		@Schema(description = "Ignore files that are already in the expected organized location.", example = "true") Boolean skipAlreadyOrganized,
		@Schema(description = "Optional category filter.", example = "[\"MEDIA\"]") List<FileCategory> onlyCategories,
		@Schema(description = "Optional subcategory filter.", example = "[\"CAMERA\"]") List<MediaSubcategory> onlySubcategories,
		@Schema(description = "Optional extension filter without dots.", example = "[\"jpg\", \"mp4\"]") List<String> onlyExtensions,
		@Schema(description = "Optional file type filter.", example = "[\"PHOTO\", \"VIDEO\"]") List<FileType> onlyFileTypes,
		@Schema(description = "Geographic subdivision inserted under the layout (default NONE).", example = "COUNTRY_STATE_CITY") LocationSubdivision locationSubdivision,
		@Schema(description = "Minimum confidence for the geographic subdivision; null accepts any confidence.", example = "HIGH") LocationConfidence locationMinConfidence,
		@Schema(description = "What to do when location is missing or below the minimum confidence (default IGNORE).", example = "IGNORE") LocationFallbackMode locationFallback) {

	/**
	 * Real ceiling for {@link #limit}, independent of the caller-supplied value -
	 * without it, a public /api/** caller could request an arbitrarily large limit
	 * and force a single {@code PageRequest.of(0, limit)} to load an absurd number
	 * of rows at once.
	 */
	private static final int MAX_LIMIT = 100_000;

	/** Backward-compatible constructor for callers without geographic options. */
	public OrganizationPreviewRequest(String sourcePath, String targetPath, Boolean recursive,
			OrganizationLayout layout, Integer limit, Boolean rebuildMetadata, List<MetadataRebuildField> rebuild,
			Boolean skipAlreadyOrganized, List<FileCategory> onlyCategories, List<MediaSubcategory> onlySubcategories,
			List<String> onlyExtensions, List<FileType> onlyFileTypes) {
		this(sourcePath, targetPath, recursive, layout, limit, rebuildMetadata, rebuild, skipAlreadyOrganized,
				onlyCategories, onlySubcategories, onlyExtensions, onlyFileTypes, LocationSubdivision.NONE, null,
				LocationFallbackMode.IGNORE);
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

	public LocationSubdivision locationSubdivisionValue() {
		return locationSubdivision == null ? LocationSubdivision.NONE : locationSubdivision;
	}

	public LocationFallbackMode locationFallbackValue() {
		return locationFallback == null ? LocationFallbackMode.IGNORE : locationFallback;
	}
}