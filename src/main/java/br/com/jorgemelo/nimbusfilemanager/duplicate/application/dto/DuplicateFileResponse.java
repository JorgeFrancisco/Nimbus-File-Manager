package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import java.time.Instant;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

public record DuplicateFileResponse(

		UUID id,

		String fileName, String extension, String fileType,

		SizeResponse size,

		String currentPath, String currentFolder,

		Instant modifiedAt) {

	public DuplicateFileResponse(Long id, String fileName, String extension, String fileType, SizeResponse size,
			String currentPath, String currentFolder, Instant modifiedAt) {
		this(UuidV7.fromLegacy(id), fileName, extension, fileType, size, currentPath, currentFolder, modifiedAt);
	}
}