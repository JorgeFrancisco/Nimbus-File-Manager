package br.com.jorgemelo.nimbusfilemanager.catalog.application.constants;

/**
 * Contract data constants of the catalog domain.
 */
public final class CatalogConstants {

	/**
	 * The shape the queued retention purge is written in, and the only one a
	 * worker will run. Read by whoever queues and checked by whoever claims - the
	 * pair only means anything if both sides name the same number.
	 */
	public static final int PAYLOAD_SCHEMA_VERSION = 1;

	private CatalogConstants() {
	}
}