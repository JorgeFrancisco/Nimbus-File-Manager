package br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants;

/**
 * Contract data constants for the duplicate domain: the preferences page key
 * for the Duplicados screen and its per-user sub-keys (active tab, view mode,
 * minimum similarity and media-type filter), the similarity bounds shown on the
 * Fotos Semelhantes screen and the fingerprint algorithm identifier the backlog
 * job and its collaborators drain against.
 */
public final class DuplicateConstants {

	/**
	 * The shape a queued fingerprint backlog is written in, and the only one a
	 * worker will run. Read by whoever queues it and checked by whoever claims it.
	 */
	public static final int FINGERPRINT_PAYLOAD_SCHEMA_VERSION = 1;

	/** What a queued backlog says about itself until a worker claims it. */
	public static final String FINGERPRINT_STARTED = "backend.duplicates.fingerprintStarted";

	/**
	 * The shape the queued deletion payload is written in, and the only one a
	 * worker will run. Read by whoever queues the request and checked by whoever
	 * claims it - the pair only means anything if both sides name the same number.
	 */
	public static final int DELETE_PAYLOAD_SCHEMA_VERSION = 1;

	/**
	 * The shape a queued similarity analysis is written in, and the only one a
	 * worker will run.
	 */
	public static final int SIMILARITY_PAYLOAD_SCHEMA_VERSION = 1;

	public static final String PAGE_KEY = "duplicates";
	public static final String TAB_KEY = "tab";
	public static final String VIEW_KEY = "view";
	public static final String MIN_SIMILARITY_KEY = "minSimilarity";
	public static final String MIN_SIMILARITY_VIDEO_KEY = "minSimilarityVideo";
	public static final String TYPE_FILTER_KEY = "fileTypes";
	public static final int MIN_SIMILARITY_PERCENT = 70;
	public static final int MAX_SIMILARITY_PERCENT = 100;
	public static final String ALGORITHM = FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1;

	private DuplicateConstants() {
	}
}