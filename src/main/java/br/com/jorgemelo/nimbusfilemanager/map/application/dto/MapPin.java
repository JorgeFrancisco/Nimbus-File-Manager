package br.com.jorgemelo.nimbusfilemanager.map.application.dto;

import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;

/**
 * One aggregated map pin: a single location holding one or more media. Never
 * one per media - media are grouped by rounded coordinate (EXIF) or by
 * administrative region (fallback). {@code pinId} is opaque to the client; it
 * is sent back to the items endpoint to fetch that pin's media.
 * {@code coverMediaId}/{@code coverFileType} point at a representative media
 * (the most recent of the group) - id, type and file name - so the client can
 * show a thumbnail marker at close zoom, and open a single-media pin straight
 * in the lightbox with the media's real name, without downloading every media.
 */
public record MapPin(String pinId, MapPinSource source, double latitude, double longitude, String label, long total,
		long photos, long videos, UUID coverMediaId, FileType coverFileType, String coverFileName) {
}