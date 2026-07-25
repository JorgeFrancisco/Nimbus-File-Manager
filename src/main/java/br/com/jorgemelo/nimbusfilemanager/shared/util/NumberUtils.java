package br.com.jorgemelo.nimbusfilemanager.shared.util;

public final class NumberUtils {

	private NumberUtils() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	public static double roundPercentage(double value) {
		return Math.round(value * 100.0) / 100.0;
	}

	public static int limit(int value, int defaultValue, int maxValue) {
		if (value <= 0) {
			return defaultValue;
		}

		return Math.min(value, maxValue);
	}

	public static int limit(Integer value, int defaultValue, int maxValue) {
		return limit(value == null ? 0 : value, defaultValue, maxValue);
	}

	public static int toInt(long value) {
		return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
	}

	public static long zeroIfNull(Long sizeBytes) {
		return sizeBytes == null ? 0 : sizeBytes;
	}

	/**
	 * Parses {@code value} into an {@link Integer}, returning {@code null} when it
	 * is null, blank or not a valid integer. Matched as-is (no trimming),
	 * preserving the strictness of {@link Integer#valueOf(String)}.
	 */
	public static Integer parseIntOrNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}

		try {
			return Integer.valueOf(value);
		} catch (NumberFormatException _) {
			return null;
		}
	}
}