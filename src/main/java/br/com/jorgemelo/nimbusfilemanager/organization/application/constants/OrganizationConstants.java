package br.com.jorgemelo.nimbusfilemanager.organization.application.constants;

/**
 * Contract data constants for the organization domain. The page/preference keys
 * are shared between OrganizationWebController (auto-saved on every
 * preview/execute submit) and SettingsWebController's Preferencias tab
 * (explicit editing) - the two are just different entry points onto the same
 * stored values.
 */
public final class OrganizationConstants {

	/**
	 * The shape the queued organization payload is written in, and the only one a
	 * worker will run. It is read by whoever queues the request and checked by
	 * whoever claims it, which is why it is not a private field of either: a
	 * request queued by one version can be claimed by another, and the pair only
	 * means anything if both sides name the same number.
	 */
	public static final int EXECUTE_PAYLOAD_SCHEMA_VERSION = 1;

	/** The same pairing, for the payload that names the run being reversed. */
	public static final int UNDO_PAYLOAD_SCHEMA_VERSION = 1;

	public static final String PAGE_KEY = "organization";
	public static final String RECURSIVE = "recursive";
	public static final String ALLOW_CONFLICTS = "allowConflicts";
	public static final String OVERWRITE_EXISTING = "overwriteExisting";
	public static final String LAYOUT = "layout";
	public static final String SIZE = "size";

	private OrganizationConstants() {
	}
}