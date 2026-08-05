package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * What one inventory pass has counted so far.
 *
 * <p>
 * These used to be fields of a step-scoped writer bean, which is how Spring
 * Batch gave each run its own copy. With the writer a plain singleton the tally
 * travels as an argument instead, so two passes cannot see each other's numbers
 * and nothing has to be scoped to anything.
 *
 * <p>
 * Atomic because the extraction inside a batch runs in parallel and reports
 * progress while it goes.
 */
public final class InventoryCounters {

	private final AtomicInteger found = new AtomicInteger();
	private final AtomicInteger analyzed = new AtomicInteger();
	private final AtomicInteger cacheHits = new AtomicInteger();
	private final AtomicInteger errors = new AtomicInteger();
	private final AtomicInteger reactivated = new AtomicInteger();

	public int found() {
		return found.get();
	}

	public int analyzed() {
		return analyzed.get();
	}

	public int cacheHits() {
		return cacheHits.get();
	}

	public int errors() {
		return errors.get();
	}

	/**
	 * How many catalogued files this pass found again after the catalog had given
	 * up on them. Not a number any screen shows: it is here because the pass has to
	 * decide, once at the end, whether anything it did changed which files a
	 * duplicate analysis may look at - and a file coming back from MISSING is
	 * exactly that.
	 */
	public int reactivated() {
		return reactivated.get();
	}

	/**
	 * @return how many had been found before this batch, which is the base the
	 * extraction adds its own progress to
	 */
	int addFound(int count) {
		return found.getAndAdd(count);
	}

	void countAnalyzed() {
		analyzed.incrementAndGet();
	}

	void countCacheHit() {
		cacheHits.incrementAndGet();
	}

	void countError() {
		errors.incrementAndGet();
	}

	void countReactivated() {
		reactivated.incrementAndGet();
	}
}