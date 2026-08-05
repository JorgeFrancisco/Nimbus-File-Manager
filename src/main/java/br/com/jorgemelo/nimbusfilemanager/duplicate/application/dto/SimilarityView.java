package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import org.springframework.data.domain.Page;

/**
 * Everything a screen needs to know about similarity, decided in the backend.
 *
 * <p>
 * The four states it distinguishes are the whole point of the durable result:
 * there is an answer; there is an answer and the library has moved since; there
 * is an answer and a new analysis is being computed; there is no answer yet. The
 * screen renders them - it does not work them out from raw fields.
 *
 * @param groups empty when there is no published answer. It is never the groups
 * of a half-built one: BUILDING is unreachable from here
 * @param outdated the composition changed since the analysis. Said exactly that
 * way, because the model can prove that the analysed set is no longer the set
 * that would be analysed - and cannot honestly claim "twenty new photos", since
 * a deletion, a restore from quarantine, a move or a new exclusion produce the
 * same evidence
 * @param analyzing an analysis of this family is queued or running. The
 * published answer stays on screen while it is
 * @param eligibleCount how many files satisfied every rule at the time of the
 * analysis; with {@code analyzedCount} it is what keeps the screen from implying
 * the whole library was looked at
 */
public record SimilarityView(Page<PublishedGroup> groups, boolean published, boolean outdated, boolean analyzing,
		int eligibleCount, int analyzedCount, int candidateLimit, boolean coverageComplete) {

	/**
	 * The neutral view, for a screen where similarity is not the subject. It exists
	 * so a shared template never evaluates an expression against an attribute that
	 * was never set - which fails when the page renders, not when it compiles.
	 */
	public static SimilarityView none() {
		return new SimilarityView(Page.empty(), false, false, false, 0, 0, 0, true);
	}
}