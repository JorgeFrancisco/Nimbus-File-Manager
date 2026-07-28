package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ProgressMath;

/**
 * Neutral background driver for a {@link SimilarityGrouping}: claims a run for
 * a threshold (unless already cached or a run is in progress), computes it
 * off-thread while tracking progress, and always releases the lock. The photo
 * and video async runner beans each hold one and only add the {@code @Async}
 * boundary, so the claim/track/release logic lives once.
 */
class SimilarityGroupingRunner {

	private static final Logger log = LoggerFactory.getLogger(SimilarityGroupingRunner.class);

	private final SimilarityGrouping grouping;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicInteger processed = new AtomicInteger();
	private final AtomicInteger total = new AtomicInteger();

	public SimilarityGroupingRunner(SimilarityGrouping grouping) {
		this.grouping = grouping;
	}

	/**
	 * Claims a background grouping for the threshold, unless already cached or a
	 * run is in progress. Returns true only when a run was claimed.
	 */
	public synchronized boolean start(int minSimilarityPercent) {
		if (grouping.isCached(minSimilarityPercent)) {
			return false;
		}

		if (!running.compareAndSet(false, true)) {
			return false;
		}

		processed.set(0);

		total.set(0);

		return true;
	}

	public void run(int minSimilarityPercent) {
		try {
			grouping.computeAndCache(minSimilarityPercent, (done, count) -> {
				processed.set(done);
				total.set(count);
			});
		} catch (RuntimeException e) {
			log.error("Similarity grouping failed for minSimilarity={}", minSimilarityPercent, e);
		} finally {
			running.set(false);
		}
	}

	public boolean isRunning() {
		return running.get();
	}

	public int processed() {
		return processed.get();
	}

	public int total() {
		return total.get();
	}

	/** Percent complete (0-100), or 0 while the candidate count is unknown. */
	public double percent() {
		int candidates = total.get();

		return candidates <= 0 ? 0 : ProgressMath.percent(processed.get(), candidates);
	}
}