package br.com.jorgemelo.nimbusfilemanager.inventory.application.constants;

/**
 * Values the inventory is defined by, rather than tuned by - the ones that
 * belong to more than the class that uses them.
 */
public final class InventoryConstants {

	/**
	 * How many files are catalogued per transaction.
	 *
	 * <p>
	 * Lives here rather than inside the runner because the telemetry records it
	 * alongside the pool sizes: a run's timings only mean something next to the
	 * batch size they were measured at, so the number is part of what a
	 * performance snapshot reports and not a private tuning knob.
	 */
	public static final int BATCH_SIZE = 100;

	private InventoryConstants() {
	}
}