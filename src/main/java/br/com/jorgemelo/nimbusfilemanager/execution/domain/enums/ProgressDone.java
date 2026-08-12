package br.com.jorgemelo.nimbusfilemanager.execution.domain.enums;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Which of the execution's counters adds up to "work already behind us".
 *
 * <p>
 * The row carries four counters and no statement of what they mean, because the
 * meaning is decided by the order of the arguments each job handler passes to
 * the progress service. It differs per workload and always has:
 * {@code filesFound} is the discovery count in an inventory, the running done
 * count in a fingerprint, the <em>total</em> in a similarity analysis and a
 * constant zero in a metadata rebuild.
 *
 * <p>
 * That was survivable while every reader belonged to one workload, and stopped
 * being survivable the moment generic readers appeared - the progress screen and
 * the activity banner serve every type at once, so they had to guess. Both
 * guessed {@code filesFound}, which is why a metadata rebuild sat at 0% for its
 * whole run while the settings screen showed the true figure, and why a
 * similarity analysis drew a full bar before comparing anything.
 *
 * <p>
 * This enum is that missing statement, named once per workload instead of
 * inferred at each reader.
 */
public enum ProgressDone {

	/** The first counter already holds the running total of concluded items. */
	FILES_FOUND,

	/** The second counter holds it, and the first means something else. */
	FILES_ANALYZED,

	/**
	 * Concluded means analysed <em>or</em> given up on: a file whose fingerprint
	 * failed is behind the drain, not ahead of it, because this run will not try it
	 * again.
	 */
	ANALYZED_AND_FAILED,

	/**
	 * Concluded means analysed, served from cache, or failed - the three outcomes
	 * an inventory item can reach. Counting only the analysed ones understates a
	 * mostly-cached rescan enormously, which is the common case on a rescan.
	 */
	ANALYZED_CACHED_AND_FAILED,

	/**
	 * Concluded items plus how far into the current one, in hundredths. The only
	 * counter that moves while a single item holds the run for hours.
	 */
	ANALYZED_WITH_ITEM_PERCENT;

	public long of(Execution execution) {
		return switch (this) {
		case FILES_FOUND -> value(execution.getFilesFound());
		case FILES_ANALYZED -> value(execution.getFilesAnalyzed());
		case ANALYZED_AND_FAILED -> value(execution.getFilesAnalyzed()) + value(execution.getErrors());
		case ANALYZED_CACHED_AND_FAILED -> value(execution.getFilesAnalyzed()) + value(execution.getCacheHits())
				+ value(execution.getErrors());
		// The item in flight contributes at most 99 hundredths: an encoder that says
		// 100% has not produced a file yet, and letting it count as a whole one drew a
		// full bar over a video still being written.
		case ANALYZED_WITH_ITEM_PERCENT -> value(execution.getFilesAnalyzed()) * 100
				+ Math.clamp(value(execution.getCurrentItemPercent()), 0, 99);
		};
	}

	private static long value(Integer count) {
		return count == null ? 0 : count;
	}
}