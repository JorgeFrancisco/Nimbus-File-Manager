package br.com.jorgemelo.nimbusfilemanager.shared.util;

import java.util.Locale;

public final class TextUtils {

	private TextUtils() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	public static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	public static String upperBlankToNull(String value) {
		String normalized = blankToNull(value);

		return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
	}
}