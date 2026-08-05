package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import java.time.LocalDateTime;

/**
 * A fingerprinted video ready for grouping: its comparison
 * {@link VideoSignature} plus the file metadata needed to build the group
 * response. Reassembled from the per-frame rows returned by the fingerprint
 * query.
 *
 * <p>
 * The catalog id sits beside the signature rather than inside it, because the
 * signature is what the comparison reads and the comparison has no business
 * knowing how a file is stored. It is here because an approved relation is keyed
 * by it, and because it is the order the greedy placement depends on.
 */
public record VideoCandidate(Long catalogFileId, VideoSignature signature, String fileName, String extension,
		long sizeBytes, String currentPath, String currentFolder, LocalDateTime modifiedAt) {
}