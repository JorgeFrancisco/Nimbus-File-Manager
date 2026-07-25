package br.com.jorgemelo.nimbusfilemanager.media.application.constants;

/**
 * Contract data constants for the media domain. The Arquivos screen persists
 * its per-user view preferences (current path, layout mode, page size and sort)
 * under the shared {@code files} page key; those sub-keys live here rather than
 * inline in the controller so the preference contract has a single, predictable
 * home.
 */
public final class MediaConstants {

	public static final String FILES_PATH_KEY = "path";
	public static final String FILES_VIEW_KEY = "view";
	public static final String FILES_SIZE_KEY = "size";
	public static final String FILES_SORT_KEY = "sort";

	private MediaConstants() {
	}
}