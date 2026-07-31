package br.com.jorgemelo.nimbusfilemanager.media.application.dto;

/**
 * What the properties dialog of the file explorer shows for one entry. A folder
 * fills {@code fileCount}/{@code folderCount} and reports the size the catalog
 * has for everything inventoried under it; a file leaves those null and reports
 * its own size, read from disk.
 */
public record ExplorerItemProperties(String name, String path, String parentPath, boolean directory, long sizeBytes,
		String sizeLabel, Long fileCount, Long folderCount, String typeLabel, String createdAtLabel,
		String modifiedAtLabel, boolean cataloged, String catalogLabel) {
}