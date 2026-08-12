package br.com.jorgemelo.nimbusfilemanager.thumbnail.application.dto;

import java.util.UUID;

/**
 * What a video thumbnail is generated from, and which generation of the bytes it
 * belongs to. The generation and not the modification time, for the reason
 * {@code PhotoThumbnailSource} gives.
 */
public record VideoThumbnailSource(UUID publicId, String currentPath, long contentRevision,
		Double durationSeconds) {
}