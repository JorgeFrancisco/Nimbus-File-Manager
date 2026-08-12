package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection;

import java.time.Instant;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

public record DuplicateFileRawResponse(

		UUID id,

		String fileName, String extension, String fileType,

		long sizeBytes,

		String currentPath, String currentFolder,

		Instant modifiedAt) {

	public DuplicateFileRawResponse(Long id, String fileName, String extension, String fileType, long sizeBytes,
			String currentPath, String currentFolder, Instant modifiedAt) {
		this(UuidV7.fromLegacy(id), fileName, extension, fileType, sizeBytes, currentPath, currentFolder, modifiedAt);
	}
}