package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.OfflineGeoDatasetStatus;

/**
 * Administrative operations over the offline geographic dataset, exposed to the
 * admin UI without revealing the concrete source. Download/update/remove are
 * admin-only actions; resolution itself is fully offline.
 */
public interface OfflineGeoDataset {

	OfflineGeoDatasetStatus status();

	/**
	 * Brings the dataset to the current remote state, importing only when there is
	 * something to import. Blocking.
	 *
	 * <p>
	 * The name is what it promises: an update whose source confirms every level
	 * unchanged, over an installation that is complete, has already achieved this
	 * and stops. That is a successful outcome, not a skipped one, which is why the
	 * answer is a plain fact about what happened rather than an error to handle.
	 *
	 * @return whether an installation happened
	 */
	boolean bringUpToDate();

	/** Removes downloaded files, extracted files and imported records. */
	void remove();
}