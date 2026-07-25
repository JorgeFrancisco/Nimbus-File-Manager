package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection;

import java.time.LocalDateTime;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

/**
 * Same shape as {@link DuplicateFileRawResponse} plus the group's sha256, used
 * to bulk-fetch the files of several duplicate groups in a single
 * {@code IN (:sha256List)} query instead of one query per group.
 */
public record DuplicateFileWithShaRawResponse(

		String sha256,

		UUID id,

		String fileName, String extension, String fileType,

		long sizeBytes,

		String currentPath, String currentFolder,

		LocalDateTime modifiedAt) {

	public DuplicateFileWithShaRawResponse(String sha256, Long id, String fileName, String extension, String fileType,
			long sizeBytes, String currentPath, String currentFolder, LocalDateTime modifiedAt) {
		this(sha256, UuidV7.fromLegacy(id), fileName, extension, fileType, sizeBytes, currentPath, currentFolder,
				modifiedAt);
	}
}