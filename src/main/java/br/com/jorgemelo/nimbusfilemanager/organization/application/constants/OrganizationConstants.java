package br.com.jorgemelo.nimbusfilemanager.organization.application.constants;

/**
 * Contract data constants for the organization domain. The page/preference keys
 * are shared between OrganizationWebController (auto-saved on every
 * preview/execute submit) and SettingsWebController's Preferencias tab
 * (explicit editing) - the two are just different entry points onto the same
 * stored values.
 */
public final class OrganizationConstants {

	public static final String PAGE_KEY = "organization";
	public static final String RECURSIVE = "recursive";
	public static final String ALLOW_CONFLICTS = "allowConflicts";
	public static final String OVERWRITE_EXISTING = "overwriteExisting";
	public static final String LAYOUT = "layout";
	public static final String SIZE = "size";

	private OrganizationConstants() {
	}
}