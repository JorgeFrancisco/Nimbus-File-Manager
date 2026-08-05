package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

/**
 * Which files an analysis was actually about.
 *
 * <p>
 * Two counts rather than one, because they are different questions and the
 * product answered only the first for years. {@code eligibleCount} is how many
 * files satisfied every functional rule - fingerprinted, active, not excluded by
 * the user - and {@code analyzedCount} is how many of those the candidate cap
 * let through. When the second is smaller, the result is true about a subset and
 * the screen has to be able to say so instead of implying the library was
 * covered (V28).
 *
 * @param digest identifies the analysed subset exactly: the ids that entered the
 * algorithm and the folder each was in, so swapping one file for another, or
 * moving one into an excluded folder, produces a different value even when the
 * counts are identical
 * @param selectionPolicy how the subset was chosen when the cap applied, so a
 * later change of policy is visible rather than silent
 */
public record SimilarityComposition(String digest, int eligibleCount, int analyzedCount, int candidateLimit,
		String selectionPolicy) {

	/**
	 * Whether every eligible file made it into the analysis. Derived rather than
	 * stored: it is the comparison of two columns that are already here, and a
	 * third column saying the same thing is a column that can disagree with them.
	 */
	public boolean coverageComplete() {
		return analyzedCount >= eligibleCount;
	}
}