package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One sampled frame of a fingerprinted video, joined with its file metadata.
 * The similarity grouping reads all frames of a video (ordered by
 * {@code sampleIndex}) and reassembles them into a per-video signature; the
 * duration and display dimensions drive the cheap candidate bucketing before
 * any SSIM runs.
 */
public record VideoFrameRawResponse(UUID id, int sampleIndex, Long positionMs, byte[] phash, byte[] luminance,
		String fileName, String extension, long sizeBytes, String currentPath, String currentFolder,
		LocalDateTime modifiedAt, Double durationSeconds, Integer width, Integer height) {
}