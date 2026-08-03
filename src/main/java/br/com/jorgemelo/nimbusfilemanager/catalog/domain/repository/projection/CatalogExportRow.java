package br.com.jorgemelo.nimbusfilemanager.catalog.domain.repository.projection;

import java.time.LocalDateTime;
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
 */
public record CatalogExportRow(@JsonIgnore Long id, UUID publicId, String fileKey, String fileName, String extension,
		Long sizeBytes, String sha256, String md5, String mimeType, String fileType, String lifecycleStatus,
		LocalDateTime createdAt, LocalDateTime modifiedAt, LocalDateTime importedAt, String currentPath,
		String originalPath) {
}