package br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums;

public enum InventoryPersistenceAction {

	CACHED, UPDATED, CREATED,

	/**
	 * An update that also brought the entry back to life - a file the catalog had
	 * given up on, found again where it always was or somewhere new.
	 *
	 * <p>
	 * Its own value rather than a flag beside {@link #UPDATED} because it is the
	 * only kind of update that changes what other parts of the product may look
	 * at: a file that is missing takes no part in a duplicate analysis, and one
	 * that comes back does. The pass counts these so it can say, once at the end,
	 * that the set of files worth comparing is not the one it was.
	 */
	REACTIVATED
}