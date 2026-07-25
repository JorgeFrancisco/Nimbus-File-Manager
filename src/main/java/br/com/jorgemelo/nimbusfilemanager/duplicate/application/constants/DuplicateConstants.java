package br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants;

/**
 * Contract data constants for the duplicate domain: the preferences page key for
 * the Duplicados screen and its per-user sub-keys (active tab, view mode,
 * minimum similarity and media-type filter), the similarity bounds shown on the
 * Fotos Semelhantes screen and the fingerprint algorithm identifier the backlog
 * job and its collaborators drain against.
 */
public final class DuplicateConstants {

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