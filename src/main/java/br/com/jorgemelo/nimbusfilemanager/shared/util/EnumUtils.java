package br.com.jorgemelo.nimbusfilemanager.shared.util;

/**
 * Lenient parsing of user-supplied strings into enum constants: a null, blank
 * or unrecognized value never throws, it resolves to the caller's fallback.
 * Centralizes the {@code try { Enum.valueOf } catch (IllegalArgumentException)}
 * template that web controllers repeated per enum type.
 */
public final class EnumUtils {

	private EnumUtils() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	/**
	 * Resolves {@code value} to a constant of {@code type}, returning
	 * {@code fallback} when it is null, blank or does not match any constant. The
	 * value is matched as-is (no trimming or case folding), preserving the
	 * strictness of {@link Enum#valueOf}.
	 */
	public static <E extends Enum<E>> E valueOfOrDefault(Class<E> type, String value, E fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}

		try {
			return Enum.valueOf(type, value);
		} catch (IllegalArgumentException _) {
			return fallback;
		}
	}

	/**
	 * Same as {@link #valueOfOrDefault(Class, String, Enum)} with a {@code null}
	 * fallback.
	 */
	public static <E extends Enum<E>> E valueOfOrNull(Class<E> type, String value) {
		return valueOfOrDefault(type, value, null);
	}
}