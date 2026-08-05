package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityComposition;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFamily;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;

/**
 * One media's similarity analysis, as the worker sees it.
 *
 * <p>
 * Two methods with different costs, and the difference is the point.
 * {@link #family} is cheap enough for a request: it reads settings and the
 * exclusion lists to say which result would answer a question, without touching
 * a fingerprint. {@link #analyze} is the O(n²) work, and it only ever runs
 * inside a claimed execution.
 *
 * <p>
 * It replaces the old {@code SimilarityGrouping}, whose two methods were "is it
 * cached?" and "compute and cache it" - a contract that only made sense while
 * the result lived in the memory of whoever computed it.
 */
interface SimilarityAnalyzer {

	FileType mediaType();

	/**
	 * The family a request for this threshold belongs to - the four components
	 * that decide which published result answers it.
	 */
	SimilarityFamily family(int minSimilarityPercent);

	/**
	 * How many files satisfy every functional rule of this analysis, before the
	 * candidate cap: fingerprinted with the right algorithm, still active in the
	 * catalog, and not hidden by the user's exclusions. The number the screen
	 * compares against what was actually analysed.
	 */
	int eligibleCount();

	/** The cap on how many of the eligible files enter the algorithm. */
	int candidateLimit();

	/**
	 * Which files an analysis started now would be about, without analysing them.
	 *
	 * <p>
	 * The application calls this to identify the work it is queueing; the worker
	 * arrives at the same answer as a by-product of loading what it analyses. Both
	 * go through the same selection primitive over rows selected by the same
	 * filter, order and limit, so "the two agree" is a property of the code rather
	 * than a hope about two queries.
	 *
	 * <p>
	 * It can still differ from what the worker ends up analysing, and that is not a
	 * failure: files arrive and leave between the request and the claim. The
	 * published result always records the composition that was really analysed.
	 */
	SimilarityComposition composition();

	/**
	 * The analysis itself, over the subset the cap allows, reporting progress as
	 * it goes.
	 */
	SimilarityAnalysisResult analyze(int minSimilarityPercent, SimilarityProgressCallback progress);
}