package br.com.jorgemelo.nimbusfilemanager.thumbnail.application.dto;

import java.util.UUID;

/**
 * What a photo thumbnail is generated from, and which generation of the bytes it
 * belongs to.
 *
 * <p>
 * The generation, and not the modification time, because the modification time
 * is not a fact about the content: an editor can preserve it across a save, and
 * it only has a second's resolution, so two different pictures can share one.
 * A cached image keyed by it would go on being served for a file it no longer
 * depicts.
 */
public record PhotoThumbnailSource(UUID publicId, String currentPath, long contentRevision, Integer rotation) {
}