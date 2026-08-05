package br.com.jorgemelo.nimbusfilemanager.organization.application.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationFallbackMode;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationSubdivision;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

/**
 * Everything an organization needs that its row cannot say.
 *
 * <p>
 * The folder, the destination and whether to recurse are columns and stay
 * there: the worker takes its locks from the columns, so a payload that also
 * carried them would open the possibility of running one path while holding
 * another.
 *
 * <p>
 * Unknown fields are ignored on purpose. A request queued by one version may be
 * claimed by the next, and the version that no longer knows a field must run
 * the request rather than refuse it. The same reasoning makes every field
 * nullable - {@code OrganizationExecuteRequest} already answers for its own
 * defaults, and a field absent from an older payload has to mean "not stated",
 * not "false".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrganizationExecutePayload(Integer schemaVersion, OrganizationLayout layout, Integer limit,
		Boolean rebuildMetadata, List<MetadataRebuildField> rebuild, Boolean skipAlreadyOrganized,
		List<FileCategory> onlyCategories, List<MediaSubcategory> onlySubcategories, List<String> onlyExtensions,
		List<FileType> onlyFileTypes, Boolean allowConflicts, Boolean overwriteExisting,
		LocationSubdivision locationSubdivision, LocationConfidence locationMinConfidence,
		LocationFallbackMode locationFallback) {
}