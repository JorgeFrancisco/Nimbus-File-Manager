package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flyway version strings, compared as versions rather than as text.
 *
 * <p>
 * Text order is wrong the moment there are ten of anything: "9" sorts after
 * "10", and a worker comparing that way would decide it was ahead of a database
 * that is ahead of it. Each dot-separated part is a number, so each part is
 * compared as one.
 */
public final class SchemaVersions {

	/**
	 * {@code V<version>__<description>.sql}, which is Flyway's own naming.
	 *
	 * <p>
	 * The version is matched as one run of digits and dots rather than as digits
	 * separated by dots. The nested form says the same thing and asks the engine
	 * to backtrack through every way of splitting it; the shape of each part is
	 * settled where it is actually needed, in {@link #compare}, which reads them
	 * as numbers.
	 */
	private static final Pattern MIGRATION = Pattern.compile("^V([\\d.]++)__.+\\.sql$");

	private SchemaVersions() {
	}

	/**
	 * The version a migration filename declares.
	 *
	 * @return the version, or {@code null} when the name is not a migration
	 */
	public static String versionOf(String filename) {
		Matcher matcher = MIGRATION.matcher(filename);

		return matcher.matches() ? matcher.group(1) : null;
	}

	/**
	 * Negative when {@code left} is older, positive when it is newer, zero when
	 * they are the same version. A part nobody wrote counts as zero, so "1" and
	 * "1.0" are the same version, which is what Flyway means by them.
	 */
	public static int compare(String left, String right) {
		String[] leftParts = left.split("\\.");
		String[] rightParts = right.split("\\.");

		for (int part = 0; part < Math.max(leftParts.length, rightParts.length); part++) {
			int difference = Long.compare(partAt(leftParts, part), partAt(rightParts, part));

			if (difference != 0) {
				return difference;
			}
		}

		return 0;
	}

	private static long partAt(String[] parts, int index) {
		return index < parts.length ? Long.parseLong(parts[index]) : 0;
	}
}