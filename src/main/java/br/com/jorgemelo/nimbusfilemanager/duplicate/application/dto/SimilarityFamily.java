package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;

/**
 * Which results compete for being <em>the</em> answer.
 *
 * <p>
 * Four components, and the composition of the library is deliberately not one
 * of them. A family holds exactly one ACTIVE result at a time; a photo arriving
 * changes what the next analysis would find, but it does not make the published
 * one stop being an answer - and if the composition were part of this, the
 * screen would go blank the moment anything was imported, because the ACTIVE
 * would no longer be found by the current key.
 *
 * <p>
 * What the composition does is travel <em>with</em> the result
 * ({@link SimilarityComposition}), so the published grouping can always say
 * which files it is about, and whoever reads it can tell that a newer analysis
 * would see more.
 *
 * @param algorithmId the fingerprint algorithm the analysis compared, so two
 * algorithms never read each other's results
 * @param groupingVersion the version of the grouping logic itself, which can
 * change while fingerprints and parameters stay identical
 * @param parametersDigest every effective parameter that can change a group -
 * the threshold, the candidate cap, the pHash radius, the video quorum, and the
 * signature of the user's duplicate exclusions
 */
public record SimilarityFamily(FileType mediaType, String algorithmId, int groupingVersion, String parametersDigest) {
}