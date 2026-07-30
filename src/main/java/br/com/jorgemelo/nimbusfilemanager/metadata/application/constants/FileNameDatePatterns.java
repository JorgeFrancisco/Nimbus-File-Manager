package br.com.jorgemelo.nimbusfilemanager.metadata.application.constants;

import java.util.regex.Pattern;

/**
 * Compiled filename date-extraction patterns shared by the {@code *MediaFamily}
 * resolvers.
 *
 * <p>
 * None of them is anchored with a surrounding {@code .*}: a pattern that
 * swallowed the whole name could only ever be matched once, and
 * {@code AbstractFileNameDateRule.parse} needs to walk the following candidates
 * when the first digit block is not a date. The leftmost match is the same
 * either way.
 */
public final class FileNameDatePatterns {

	/** {@code yyyyMMdd} then {@code _HHmmss} (underscore separator). */
	public static final Pattern DATE8_UNDERSCORE_TIME6 = Pattern.compile("(\\d{8})_(\\d{6})");

	/** {@code yyyyMMdd} then {@code _}/{@code -} then {@code HHmmss}. */
	public static final Pattern DATE8_SEP_TIME6 = Pattern.compile("(\\d{8})[_-](\\d{6})");

	/**
	 * {@code yyyyMMddHHmmss} with no separator, and exactly that long: the digit
	 * guards on both sides keep it from matching inside a longer numeric run, since
	 * a 19-digit share id is not a timestamp.
	 */
	public static final Pattern DATE8_TIME6 = Pattern.compile("(?<!\\d)(\\d{8})(\\d{6})(?!\\d)");

	/** Bare {@code yyyyMMdd} block anywhere in the name. */
	public static final Pattern DATE8 = Pattern.compile("(\\d{8})");

	private FileNameDatePatterns() {
	}
}