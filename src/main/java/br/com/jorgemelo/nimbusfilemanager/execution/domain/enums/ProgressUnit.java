package br.com.jorgemelo.nimbusfilemanager.execution.domain.enums;

/**
 * What one unit of progress is, for a workload that counts them.
 *
 * <p>
 * Recorded because a rate is only meaningful against a unit: nine stages of a
 * dataset update are not nine files, and a rate over them says nothing about
 * when the update ends. The unit is what makes {@link EtaState#NOT_APPLICABLE}
 * an honest answer rather than a missing feature.
 */
public enum ProgressUnit {

	/** Files of the user's library. */
	FILES,

	/** Items of some other population - movements, quarantined entries, plans. */
	ITEMS,

	/**
	 * Hundredths of an item, for work whose single item can occupy the run for
	 * hours. A batch of one long video advances visibly in these and not at all in
	 * whole files, and an estimate over whole files would have nothing to divide by
	 * until the first one finished.
	 */
	HUNDREDTHS,

	/**
	 * Named stages of a pipeline, whose costs are not comparable to each other. A
	 * workload measured in these has no honest single rate.
	 */
	STAGES
}