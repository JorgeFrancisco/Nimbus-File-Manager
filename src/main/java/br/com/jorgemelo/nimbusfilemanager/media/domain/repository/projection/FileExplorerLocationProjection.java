package br.com.jorgemelo.nimbusfilemanager.media.domain.repository.projection;

import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;

public interface FileExplorerLocationProjection {

	Long getCatalogFileId();

	UUID getPublicId();

	String getFileName();

	FileType getFileType();

	Long getSizeBytes();

	String getCurrentPath();
}