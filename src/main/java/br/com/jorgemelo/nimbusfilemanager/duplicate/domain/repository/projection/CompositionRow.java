package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection;

import java.util.UUID;

/**
 * One row of the light projection used to identify an analysis without loading
 * it: the file, and the folder that decides whether it is eligible.
 *
 * <p>
 * It exists so the application can compute the composition digest of the
 * analysis it is about to ask for, at the cost of two columns per candidate
 * instead of a 256-bit hash and a luminance sample each. The queries that
 * produce it repeat the heavy ones' {@code WHERE}, {@code ORDER BY} and limit
 * exactly, because the two have to select the same files in the same order for
 * the digest to mean anything.
 */
public record CompositionRow(UUID mediaPublicId, String currentFolder) {
}