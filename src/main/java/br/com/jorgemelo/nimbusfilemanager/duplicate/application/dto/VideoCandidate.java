package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import java.time.LocalDateTime;

/**
 * A fingerprinted video ready for grouping: its comparison
 * {@link VideoSignature} plus the file metadata needed to build the group
 * response. Reassembled from the per-frame rows returned by the fingerprint
 * query.
 */
public record VideoCandidate(VideoSignature signature, String fileName, String extension, long sizeBytes,
		String currentPath, String currentFolder, LocalDateTime modifiedAt) {
}