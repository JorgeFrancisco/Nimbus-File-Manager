package br.com.jorgemelo.nimbusfilemanager.update.domain.enums;

/**
 * Where an update install is, for the screen to show something other than a
 * frozen page.
 *
 * <p>
 * The download is the long step - a hundred and twenty megabytes, a minute on a
 * normal connection - and it used to happen inside the request that asked for
 * it, so the browser waited with nothing on screen and the page only came back
 * when it was already over. These phases exist so that wait is visible.
 */
public enum UpdatePhase {

	/** Nothing running. */
	IDLE,
	/** Fetching the installer. */
	DOWNLOADING,
	/** Comparing what arrived against the published checksum. */
	VERIFYING,
	/** The installer was started and this run is about to end. */
	STARTING,
	/** It stopped before installing; the reason is reported separately. */
	FAILED
}