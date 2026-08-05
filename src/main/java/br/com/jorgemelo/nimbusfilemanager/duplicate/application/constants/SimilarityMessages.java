package br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;

/**
 * What a similarity analysis writes on its execution, as a key and its
 * arguments.
 *
 * <p>
 * Never as resolved text: the analysis runs in the worker, which has no request
 * behind it and therefore no language.
 */
public final class SimilarityMessages {

	public static ExecutionMessage analysisStarted(int analyzed, int eligible) {
		return of("backend.duplicates.similarityStarted", analyzed, eligible);
	}

	/**
	 * Both counts, always. A run that grouped 40 sets out of 8.000 of 98.000 files
	 * is a different fact from one that grouped 40 out of everything, and the row
	 * is where somebody looks to tell them apart.
	 */
	public static ExecutionMessage analysisCompleted(int groups, int analyzed, int eligible) {
		return of("backend.duplicates.similarityCompleted", groups, analyzed, eligible);
	}

	/**
	 * The analysis that was asked for is no longer the analysis this installation
	 * would run - a threshold, a tuning value or an exclusion changed in between.
	 * Said rather than substituted: the screen asks for the current one by itself.
	 */
	public static ExecutionMessage definitionChanged() {
		return of("backend.duplicates.similarityDefinitionChanged");
	}

	/**
	 * Stopped because somebody asked it to. Worth its own sentence rather than a
	 * completion with zero groups: nothing was published, so the answer the screen
	 * shows is still the one from before, and a person who cancelled needs to know
	 * that is what they are looking at.
	 */
	public static ExecutionMessage analysisCancelled() {
		return of("backend.duplicates.similarityCancelled");
	}

	/**
	 * It finished, and it lost. Another analysis became the answer while this one
	 * was being written down, so what it produced was discarded rather than
	 * published over a newer result. Said plainly, because a row reading
	 * "completed" over an answer nobody can find would be worse than a failure.
	 */
	public static ExecutionMessage analysisSuperseded() {
		return of("backend.duplicates.similaritySuperseded");
	}

	/**
	 * An incremental update was asked for over a medium that keeps no relations to
	 * update from. Refused rather than silently answered with a full comparison:
	 * the two cost minutes apart, and whoever asked would have no way of telling
	 * which one they got.
	 */
	public static ExecutionMessage incrementalUnavailable() {
		return of("backend.duplicates.similarityIncrementalUnavailable");
	}

	private static ExecutionMessage of(String code, Object... args) {
		return new ExecutionMessage(code, List.of(args));
	}

	private SimilarityMessages() {
	}
}