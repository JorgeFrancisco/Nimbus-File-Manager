package br.com.jorgemelo.nimbusfilemanager.shared.util;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

public final class PathUtils {

	private PathUtils() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	/**
	 * True when any segment of {@code path} (split on both {@code /} and
	 * {@code \\}) equals any of {@code folderNames}, ignoring case and surrounding
	 * whitespace. Low-level, source-agnostic helper so every media family shares
	 * one folder-matching implementation without a package cycle.
	 */
	public static boolean containsAnyFolder(String path, String... folderNames) {
		if (path == null || path.isBlank() || folderNames == null || folderNames.length == 0) {
			return false;
		}

		String[] parts = path.replace('\\', '/').split("/");

		return Arrays.stream(parts).map(PathUtils::normalizeFolder)
				.anyMatch(part -> Arrays.stream(folderNames).map(PathUtils::normalizeFolder).anyMatch(part::equals));
	}

	private static String normalizeFolder(String value) {
		return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
	}

	public static String normalize(Path path) {
		return path.toAbsolutePath().normalize().toString();
	}

	public static Path normalizePath(String path) {
		return Path.of(path).toAbsolutePath().normalize();
	}

	public static String normalize(String path) {
		return normalize(Path.of(path));
	}

	public static String normalizeLower(String path) {
		return normalize(path).toLowerCase(Locale.ROOT);
	}

	/**
	 * Builds a LIKE pattern matching every descendant path of {@code folder}. On
	 * Windows the separator is a backslash, which is a LIKE escape char, and file
	 * names routinely contain '_' (a LIKE wildcard); both, plus '%', are escaped
	 * with a leading backslash so the prefix matches literally. Works for a drive
	 * root (already ends with the separator) too.
	 *
	 * <p>
	 * The pattern uses backslash as the LIKE escape character. In native PostgreSQL
	 * that is the default; in HQL/JPQL a bound LIKE parameter treats backslash as a
	 * literal unless the query declares it, so the {@code @Query} must pair this
	 * with {@code like :pattern escape '\'} (written {@code escape '\\'} inside a
	 * text block). Without that clause HQL would match zero rows for any Windows
	 * path.
	 */
	public static String descendantLikePattern(String folder, String separator) {
		String prefix = folder.endsWith(separator) ? folder : folder + separator;
		String escaped = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");

		return escaped + "%";
	}
}