package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import java.util.UUID;

/**
 * One file of a published group, in the public API.
 *
 * <p>
 * Identified by public id, never by internal id, and carrying the decision the
 * analysis reached rather than leaving a consumer to work it out. {@code path}
 * and {@code fileName} are read from the catalog now, so a file moved since the
 * analysis reports where it is - and a file whose record is gone reports
 * {@code null} with {@code actionable} false.
 */
public record SimilarityMemberResponse(UUID id, String fileName, String path, Long sizeBytes, String verdict,
		String reason, boolean actionable) {
}