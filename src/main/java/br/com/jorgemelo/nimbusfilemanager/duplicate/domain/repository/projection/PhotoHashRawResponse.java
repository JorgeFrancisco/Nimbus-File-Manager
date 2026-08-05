package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

/**
 * Raw projection used to build photo-similarity groups - see
 * {@code PhotoSimilarityService}.
 *
 * <p>
 * Carries both identifiers on purpose. The public id is what a published group
 * names its members by; the catalog id is what the analysis is ordered by and
 * what an approved relation is keyed on, so having it here saves translating
 * between the two for every pair.
 */
public record PhotoHashRawResponse(

		Long catalogFileId,

		UUID id, byte[] phash, byte[] luminance,

		String fileName, String extension,

		long sizeBytes,

		String currentPath, String currentFolder,

		LocalDateTime modifiedAt) {

	public PhotoHashRawResponse(Long id, byte[] phash, byte[] luminance, String fileName, String extension,
			long sizeBytes, String currentPath, String currentFolder, LocalDateTime modifiedAt) {
		this(id, UuidV7.fromLegacy(id), phash, luminance, fileName, extension, sizeBytes, currentPath, currentFolder,
				modifiedAt);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}

		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		PhotoHashRawResponse other = (PhotoHashRawResponse) o;

		return sizeBytes == other.sizeBytes && Objects.equals(catalogFileId, other.catalogFileId)
				&& Objects.equals(id, other.id) && Arrays.equals(phash, other.phash)
				&& Arrays.equals(luminance, other.luminance) && Objects.equals(fileName, other.fileName)
				&& Objects.equals(extension, other.extension) && Objects.equals(currentPath, other.currentPath)
				&& Objects.equals(currentFolder, other.currentFolder) && Objects.equals(modifiedAt, other.modifiedAt);
	}

	@Override
	public int hashCode() {
		return Objects.hash(catalogFileId, id, Arrays.hashCode(phash), Arrays.hashCode(luminance), fileName,
				extension, sizeBytes, currentPath, currentFolder, modifiedAt);
	}

	@Override
	public String toString() {
		return "PhotoHashRawResponse[catalogFileId=" + catalogFileId + ", id=" + id + ", phash="
				+ Arrays.toString(phash) + ", luminance=" + Arrays.toString(luminance) + ", fileName=" + fileName
				+ ", extension=" + extension + ", sizeBytes=" + sizeBytes + ", currentPath=" + currentPath
				+ ", currentFolder=" + currentFolder + ", modifiedAt=" + modifiedAt + "]";
	}
}