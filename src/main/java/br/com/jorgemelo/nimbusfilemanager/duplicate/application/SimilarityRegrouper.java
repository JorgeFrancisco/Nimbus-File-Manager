package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;

/**
 * An analyser that can also answer from what it already approved.
 *
 * <p>
 * Separate from {@link SimilarityAnalyzer} because the capability is separate:
 * regrouping needs durable relations, and only the photo analysis stores them.
 * A medium without them cannot regroup at all, and a type that says so is
 * better than a method every implementation would have to refuse at runtime.
 */
interface SimilarityRegrouper {

	/**
	 * The grouping a rebuild would produce, over the relations already approved -
	 * no distance scan and no SSIM.
	 *
	 * <p>
	 * Sound for a removal and only for a removal. Whether two files relate does
	 * not depend on which other files exist, so the relations that survive a
	 * deletion are exactly the relations a rebuild would recompute; what a removal
	 * does change is the grouping, because the greedy placement is order-dependent
	 * and a file that was refused because of the departed one would now be
	 * accepted. Running the whole grouping again - rather than editing the
	 * published groups - is what makes those refusals be taken again.
	 *
	 * <p>
	 * It is <em>not</em> sound after an arrival: a file nothing was ever compared
	 * against has no relations, and this would publish a result that silently
	 * ignored it.
	 */
	SimilarityAnalysisResult regroup(int minSimilarityPercent, SimilarityProgressCallback progress);
}