package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

/**
 * Whether the published analysis still describes the library, answered on its
 * own rather than as part of building the screen.
 *
 * <p>
 * Two fields and not one because "no analysis yet" is not "current": a screen
 * that has never been analysed must say nothing rather than claim its absent
 * answer is up to date.
 *
 * @param published a grouping exists for these parameters
 * @param outdated the composition changed since it was analysed; meaningless,
 * and always false, when nothing is published
 */
public record SimilarityFreshness(boolean published, boolean outdated) {

	/**
	 * Nothing published cannot be out of date, and letting the pair say so would
	 * put a screen in a state no rule produces.
	 */
	public SimilarityFreshness {
		if (!published && outdated) {
			throw new IllegalArgumentException("An analysis that was never published cannot be outdated");
		}
	}

	public static SimilarityFreshness notPublished() {
		return new SimilarityFreshness(false, false);
	}
}