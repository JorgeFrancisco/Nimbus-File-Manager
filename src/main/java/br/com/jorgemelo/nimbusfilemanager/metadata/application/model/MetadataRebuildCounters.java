package br.com.jorgemelo.nimbusfilemanager.metadata.application.model;

public class MetadataRebuildCounters {

	public int processed;
	public int candidates;
	public int rebuilt;
	public int skippedMissing;
	public int skippedWithoutLocation;
	public int skippedUnsupportedType;
	public int errors;

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
}