package br.com.jorgemelo.nimbusfilemanager.metadata.application.model;

/**
 * What a rebuild did, counted while it runs.
 *
 * <p>
 * The fields are private and every increment is named, because these numbers
 * are the whole report of an operation that can run for hours. A caller able to
 * assign to them is a caller able to correct one silently - and the difference
 * between "skipped because the file is gone" and "skipped because it carries no
 * location" is the difference between a library that moved and a rebuild that
 * simply had nothing to do.
 */
public class MetadataRebuildCounters {

	private int processed;
	private int candidates;
	private int rebuilt;
	private int skippedMissing;
	private int skippedWithoutLocation;
	/**
	 * Reported by the response and folded by {@link #add}, but never incremented:
	 * nothing in the rebuild classifies a file that way today. It has no counting
	 * method for that reason - one would be dead code, and the day the case exists
	 * the method is written with the code that calls it.
	 */
	private int skippedUnsupportedType;
	private int errors;

	/**
	 * Folds a batch's counters into this total. Used so that a batch retried after
	 * an optimistic-lock conflict is counted once - the batch runs on a fresh
	 * counter each attempt and only the successful attempt is added.
	 */
	public void add(MetadataRebuildCounters batch) {
		this.processed += batch.processed;
		this.candidates += batch.candidates;
		this.rebuilt += batch.rebuilt;
		this.skippedMissing += batch.skippedMissing;
		this.skippedWithoutLocation += batch.skippedWithoutLocation;
		this.skippedUnsupportedType += batch.skippedUnsupportedType;
		this.errors += batch.errors;
	}

	public void countProcessed() {
		processed++;
	}

	public void countCandidate() {
		candidates++;
	}

	public void countRebuilt() {
		rebuilt++;
	}

	public void countSkippedMissing() {
		skippedMissing++;
	}

	public void countSkippedWithoutLocation() {
		skippedWithoutLocation++;
	}

	public void countError() {
		errors++;
	}

	public int processed() {
		return processed;
	}

	public int candidates() {
		return candidates;
	}

	public int rebuilt() {
		return rebuilt;
	}

	public int skippedMissing() {
		return skippedMissing;
	}

	public int skippedWithoutLocation() {
		return skippedWithoutLocation;
	}

	public int skippedUnsupportedType() {
		return skippedUnsupportedType;
	}

	public int errors() {
		return errors;
	}
}