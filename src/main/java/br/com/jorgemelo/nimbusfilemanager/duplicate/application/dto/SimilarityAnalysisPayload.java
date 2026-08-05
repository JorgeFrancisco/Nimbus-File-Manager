package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The request for one similarity analysis, as it travels in the queue.
 *
 * <p>
 * Two digests, and they are opposites on purpose - which is the distinction the
 * whole model rests on.
 *
 * <p>
 * The <b>composition</b> is the world. It may move between the request and the
 * claim, because a library keeps receiving photos, and the worker analyses the
 * snapshot it finds and publishes that. A difference is a normal condition.
 *
 * <p>
 * The <b>parameters</b> are the intention. They are what makes two analyses
 * different work rather than the same work over different files, and they are
 * what the deduplication key is built from. If they change after the request -
 * the user hides a folder from comparison, an installation is restarted with
 * different tuning - then this execution can no longer carry out what was asked.
 * It says so and ends; it does not quietly perform a different analysis under a
 * request nobody made. The screen asks for the new family on its own, because
 * there is no published answer for it.
 *
 * @param expectedParametersDigest the whole definition in 64 characters, rather
 * than a copy of every setting and exclusion list: what matters is whether it is
 * still the same, and that is exactly what a digest answers
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SimilarityAnalysisPayload(Integer schemaVersion, Integer minSimilarityPercent,
		String expectedParametersDigest, String expectedCompositionDigest) {
}