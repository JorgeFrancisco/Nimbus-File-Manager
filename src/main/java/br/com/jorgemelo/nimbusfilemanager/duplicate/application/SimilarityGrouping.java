package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

/**
 * A cacheable similarity grouping (photo or video) as seen by the neutral
 * {@link SimilarityGroupingRunner}: whether a threshold is already cached, and
 * how to compute and cache it while reporting progress. Lets the background
 * runner drive either media without knowing which.
 */
interface SimilarityGrouping {

	boolean isCached(int minSimilarityPercent);

	void computeAndCache(int minSimilarityPercent, SimilarityProgressCallback progress);
}