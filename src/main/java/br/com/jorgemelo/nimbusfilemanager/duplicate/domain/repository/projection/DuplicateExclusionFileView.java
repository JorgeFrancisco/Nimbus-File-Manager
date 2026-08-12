package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * One file exclusion as the settings screen lists it.
 *
 * @param applies whether the judgement still bears on the file that is there -
 * false once the content it was made about has been replaced. The row is kept
 * either way: the user did say it, and what changed is what it is about
 */
public record DuplicateExclusionFileView(Long id, UUID catalogFilePublicId, String currentPath, Instant createdAt,
		boolean applies) {
}