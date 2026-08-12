package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One sampled frame of a fingerprinted video, joined with its file metadata.
 * The similarity grouping reads all frames of a video (ordered by
 * {@code sampleIndex}) and reassembles them into a per-video signature; the
 * duration and display dimensions drive the cheap candidate bucketing before
 * any SSIM runs.
 *
 * <p>
 * Carries both identifiers, like its photo counterpart and for the same reason.
 * The public id is what a published group names its members by; the catalog id
 * is what the analysis is ordered by and what an approved relation is keyed on,
 * so having it here saves translating between the two for every pair.
 */
public record VideoFrameRawResponse(Long catalogFileId, UUID id, int sampleIndex, Long positionMs, byte[] phash,
		byte[] luminance, String fileName, String extension, long sizeBytes, String currentPath, String currentFolder,
		Instant modifiedAt, Double durationSeconds, Integer width, Integer height) {

	/**
	 * By content, because two of the components are arrays and a record's generated
	 * equality would compare them by identity - so two rows carrying the same frame
	 * would read as different. Its photo counterpart carries the same overrides for
	 * the same reason.
	 */
	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}

		if (other == null || getClass() != other.getClass()) {
			return false;
		}

		VideoFrameRawResponse row = (VideoFrameRawResponse) other;

		return sampleIndex == row.sampleIndex && sizeBytes == row.sizeBytes
				&& Objects.equals(catalogFileId, row.catalogFileId) && Objects.equals(id, row.id)
				&& Objects.equals(positionMs, row.positionMs) && Arrays.equals(phash, row.phash)
				&& Arrays.equals(luminance, row.luminance) && Objects.equals(fileName, row.fileName)
				&& Objects.equals(extension, row.extension) && Objects.equals(currentPath, row.currentPath)
				&& Objects.equals(currentFolder, row.currentFolder) && Objects.equals(modifiedAt, row.modifiedAt)
				&& Objects.equals(durationSeconds, row.durationSeconds) && Objects.equals(width, row.width)
				&& Objects.equals(height, row.height);
	}

	@Override
	public int hashCode() {
		return Objects.hash(catalogFileId, id, sampleIndex, positionMs, Arrays.hashCode(phash),
				Arrays.hashCode(luminance), fileName, extension, sizeBytes, currentPath, currentFolder, modifiedAt,
				durationSeconds, width, height);
	}

	@Override
	public String toString() {
		return "VideoFrameRawResponse[catalogFileId=" + catalogFileId + ", id=" + id + ", sampleIndex=" + sampleIndex
				+ ", positionMs=" + positionMs + ", phash=" + Arrays.toString(phash) + ", luminance="
				+ Arrays.toString(luminance) + ", fileName=" + fileName + ", extension=" + extension + ", sizeBytes="
				+ sizeBytes + ", currentPath=" + currentPath + ", currentFolder=" + currentFolder + ", modifiedAt="
				+ modifiedAt + ", durationSeconds=" + durationSeconds + ", width=" + width + ", height=" + height + "]";
	}
}