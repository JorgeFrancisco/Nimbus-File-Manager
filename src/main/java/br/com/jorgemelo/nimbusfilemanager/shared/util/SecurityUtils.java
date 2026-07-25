package br.com.jorgemelo.nimbusfilemanager.shared.util;

import org.springframework.security.core.Authentication;

/**
 * Small helpers around Spring Security's {@link Authentication}. Centralizes
 * the "username or fallback" ternary that the web controllers repeated per
 * screen (the fallback differs per screen, so it stays a caller-supplied
 * argument).
 */
public final class SecurityUtils {

	private SecurityUtils() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	/**
	 * The authenticated username, or {@code fallback} when there is no
	 * authentication. The {@code /app} screens are login-protected, so the fallback
	 * only covers the defensive anonymous path.
	 */
	public static String usernameOr(Authentication authentication, String fallback) {
		return authentication == null ? fallback : authentication.getName();
	}
}