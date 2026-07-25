package br.com.jorgemelo.nimbusfilemanager.security.application.constants;

/**
 * Contract data constants for the security domain: the access-log event-type
 * codes recorded by the authentication handlers and read back by the access
 * screens, the session attribute that carries the pending username between the
 * password step and the two-factor step, and the preferences page key for the
 * Usuarios screen.
 */
public final class SecurityConstants {

	public static final String PAGE_KEY = "users";
	public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
	public static final String LOGIN_FAILURE = "LOGIN_FAILURE";
	public static final String LOGIN_2FA_REQUIRED = "LOGIN_2FA_REQUIRED";
	public static final String LOGIN_2FA_SUCCESS = "LOGIN_2FA_SUCCESS";
	public static final String LOGIN_2FA_FAILURE = "LOGIN_2FA_FAILURE";
	public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
	public static final String LOGOUT = "LOGOUT";
	public static final String LOGOUT_INACTIVITY = "LOGOUT_INACTIVITY";
	public static final String PENDING_USERNAME = "TWO_FACTOR_USERNAME";

	private SecurityConstants() {
	}
}