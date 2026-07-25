package br.com.jorgemelo.nimbusfilemanager.security.application.constants;

/**
 * Stable message codes recorded in the access-log {@code message} column. Each
 * constant's value is the i18n bundle key that renders it, so the writers store
 * a locale-neutral code and the access screen resolves the label on read - no
 * English text is ever persisted or shown hard-coded.
 */
public final class AccessMessages {

	public static final String LOGIN_COMPLETED = "backend.access.loginCompleted";
	public static final String GOOGLE_LOGIN_COMPLETED = "backend.access.googleLoginCompleted";
	public static final String TWO_FACTOR_REQUIRED = "backend.access.twoFactorRequired";
	public static final String TWO_FACTOR_REQUIRED_GOOGLE = "backend.access.twoFactorRequiredGoogle";
	public static final String TWO_FACTOR_COMPLETED = "backend.access.twoFactorCompleted";
	public static final String TWO_FACTOR_REJECTED_LOCKED = "backend.access.twoFactorRejectedLocked";
	public static final String INVALID_TWO_FACTOR_CODE = "backend.access.invalidTwoFactorCode";
	public static final String ACCOUNT_LOCKED = "backend.access.accountLocked";
	public static final String ACCOUNT_NOT_CONFIRMED = "backend.access.accountNotConfirmed";
	public static final String ACCOUNT_TEMPORARILY_LOCKED = "backend.access.accountTemporarilyLocked";
	public static final String INVALID_CREDENTIALS = "backend.access.invalidCredentials";
	public static final String LOGOUT_COMPLETED = "backend.access.logoutCompleted";
	public static final String LOGOUT_BY_INACTIVITY = "backend.access.logoutByInactivity";

	private AccessMessages() {
	}
}