package br.com.jorgemelo.nimbusfilemanager.metadata.application.constants;

/**
 * Page and preference keys that remember what the admin last asked the metadata
 * rebuild panel for, shared between the panel actions and its read-side model.
 * Separate from {@code MetadataConstants} because that one holds the pHash
 * dimensions - a different, self-contained group.
 */
public final class MetadataRebuildPreferences {

	public static final String PAGE_KEY = "metadata-rebuild";
	public static final String SOURCE_PATH_KEY = "sourcePath";
	public static final String FIELDS_KEY = "fields";
	public static final String DRY_RUN_KEY = "dryRun";
	public static final String SCOPE_KEY = "scope";

	/**
	 * When the last run started, which is the cutoff a continuing run skips by. Kept
	 * with the choices because it is what makes them mean anything on the next run,
	 * and it is per user like the rest of the panel.
	 */
	public static final String LAST_RUN_KEY = "lastRunStartedAt";

	private MetadataRebuildPreferences() {
	}
}