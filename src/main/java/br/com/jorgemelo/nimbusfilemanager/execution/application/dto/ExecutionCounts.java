package br.com.jorgemelo.nimbusfilemanager.execution.application.dto;

/**
 * What a command did, in the four numbers every one of them reports: how many
 * items it was given, how many it carried out, how many it left alone and how
 * many failed.
 *
 * <p>
 * A parameter object rather than four more arguments, and named rather than
 * positional, because the columns they land in do not read as what they mean -
 * {@code cache_hits} has held "skipped" since the inventory needed a name for
 * it, and a call site passing four bare integers into that is a call site
 * nobody can check.
 */
public record ExecutionCounts(int found, int moved, int skipped, int failed) {

	/** One item, carried out. The shape of every command over a single path. */
	public static ExecutionCounts one() {
		return new ExecutionCounts(1, 1, 0, 0);
	}
}