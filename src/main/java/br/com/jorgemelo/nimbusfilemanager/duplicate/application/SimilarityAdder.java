package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;

/**
 * An analyser that can incorporate what arrived without re-examining what did
 * not.
 *
 * <p>
 * A second capability rather than a method on {@link SimilarityAnalyzer},
 * for the same reason {@link SimilarityRegrouper} is one: it needs durable
 * relations and a record of which files they cover, and only the photo analysis
 * keeps either. A medium without them is turned away by a type rather than by a
 * runtime refusal every implementation would have to write.
 *
 * <p>
 * Separate from {@code SimilarityRegrouper} because the two are not the same
 * promise. A regroup compares nothing and is sound only for a removal; this one
 * compares, and is what an arrival needs.
 */
interface SimilarityAdder {

	/**
	 * Incorporates every eligible file that is not yet part of the relation
	 * universe, then groups - producing the answer a full rebuild would produce
	 * over the same final set.
	 *
	 * <p>
	 * The newcomers are compared against the <em>covered</em> files and against
	 * each other, never against the merely eligible ones. A file that is covered
	 * but hidden today is still part of the universe, and skipping it would leave
	 * the pair between it and the newcomer evaluated by nobody - after which both
	 * are marked covered and no later run ever looks again.
	 * {@code SimilarityCoverageModelTest} holds that counterexample.
	 *
	 * <p>
	 * Pairs of two already-covered files are not asked about, and that is not an
	 * approximation: whether two images look alike is a fact about the two of
	 * them, and neither was touched. A pair whose verdict could have changed has a
	 * re-fingerprinted file in it, and such a file is not covered - its relations
	 * and its coverage were forgotten together, so it arrives here as new.
	 */
	SimilarityAnalysisResult add(int minSimilarityPercent, SimilarityProgressCallback progress);
}