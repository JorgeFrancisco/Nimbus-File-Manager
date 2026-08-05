package br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.Phase;

/**
 * What a dataset update is doing right now, as the settings panel shows it:
 * which administrative level, whether it is being downloaded or imported, and
 * how far into it the run is.
 *
 * <p>
 * It used to carry bytes and a running record count as well, read from a
 * singleton in the process doing the work. Those are gone, and deliberately: the
 * work happens in the worker, so what the panel can honestly show is what the
 * execution row keeps - and a byte counter that changes ten times a second is
 * not something a row keeps. The record total is still reported, by the dataset
 * status, once the import has published it.
 *
 * @param stepLabel i18n key of the administrative level, resolved by the page.
 * @param percent 0-100 within the current step, or -1 when not known yet.
 */
public record Snapshot(Phase phase, String stepLabel, double percent) {

	/** Nothing is going on: no update is running. */
	public static Snapshot idle() {
		return new Snapshot(Phase.IDLE, "", -1);
	}

	public boolean downloading() {
		return phase == Phase.DOWNLOADING;
	}

	public boolean importing() {
		return phase == Phase.IMPORTING;
	}
}