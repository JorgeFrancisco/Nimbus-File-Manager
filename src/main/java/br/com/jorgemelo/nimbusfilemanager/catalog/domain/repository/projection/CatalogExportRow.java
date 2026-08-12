package br.com.jorgemelo.nimbusfilemanager.catalog.domain.repository.projection;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * One catalog row streamed by the catalog export. A flat projection of
 * {@code catalog_file} joined to its {@code catalog_file_location}, so the
 * export carries no lazy associations and can be written outside any
 * transaction.
 *
 * <p>
 * The internal {@code id} is present only to drive keyset pagination during the
 * export; it is {@link JsonIgnore}d and omitted from the CSV so the public
 * identity stays {@code publicId}.
 *
 * <p>
 * The three timestamps are instants, because that is what the catalog holds:
 * when a file was written to and when it entered the catalog are moments, not
 * readings on somebody's wall clock. The export renders them with their offset
 * rather than dropping it - a row exported in one time zone and read in another
 * used to be off by hours with nothing in the file to say so.
 */
public record CatalogExportRow(@JsonIgnore Long id, UUID publicId, String fileName, String extension,
		Long sizeBytes, String sha256, String mimeType, String fileType, String lifecycleStatus,
		Instant createdAt, Instant modifiedAt, Instant importedAt, String currentPath) {
}