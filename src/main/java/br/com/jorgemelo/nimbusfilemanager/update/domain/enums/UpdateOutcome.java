package br.com.jorgemelo.nimbusfilemanager.update.domain.enums;

/**
 * What came of asking for an update to be installed.
 *
 * <p>
 * Every value other than {@link #STARTED} is a refusal the person who asked has
 * to be told about, with its reason - a screen that reported only "nothing
 * happened" would be read as success. The reason is a value here rather than a
 * sentence so the text stays in the message bundles, where every other text the
 * user reads lives.
 */
public enum UpdateOutcome {

	/** The installer was verified and started; this run is ending. */
	STARTED,
	/** Nothing newer than what is already installed. */
	NOTHING_TO_INSTALL,
	/** The installer is an MSI, and only Windows can run one. */
	UNSUPPORTED_PLATFORM,
	/** The download did not complete - offline, refused, interrupted. */
	DOWNLOAD_FAILED,
	/** The release published no checksum this could read. */
	CHECKSUM_UNAVAILABLE,
	/** What arrived is not what was published, so it is not installed. */
	CHECKSUM_MISMATCH,
	/** The installer could not be started once verified. */
	COULD_NOT_START
}