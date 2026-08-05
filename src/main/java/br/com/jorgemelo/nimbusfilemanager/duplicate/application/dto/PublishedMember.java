package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.SimilarityMemberFile;

/**
 * A member of a published group as the screen receives it: what the analysis
 * decided, joined to what the catalog says about the file today.
 *
 * @param file {@code null} when the catalog row is gone entirely - the analysis
 * still records that the file was part of the group, and there is nothing left
 * to show or act upon
 * @param actionable whether the screen may offer delete or exclude over it. The
 * decision is taken here, in the backend, rather than being inferred on the
 * screen from a status it would have to interpret
 */
public record PublishedMember(AnalyzedMember decision, SimilarityMemberFile file, boolean actionable) {
}