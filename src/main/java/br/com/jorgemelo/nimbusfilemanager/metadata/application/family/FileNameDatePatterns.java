package br.com.jorgemelo.nimbusfilemanager.metadata.application.family;

import java.util.regex.Pattern;

/**
 * Compiled filename date-extraction patterns shared by the {@code *MediaFamily}
 * resolvers. Case-insensitive to preserve the previous extraction behaviour
 * ({@code AbstractFileNameDateRule.parse} compiled with
 * {@code CASE_INSENSITIVE}).
 */
public final class FileNameDatePatterns {

	/** {@code yyyyMMdd} then {@code _HHmmss} (underscore separator). */
	static final Pattern DATE8_UNDERSCORE_TIME6 = Pattern.compile(".*?(\\d{8})_(\\d{6}).*", Pattern.CASE_INSENSITIVE);

	/** {@code yyyyMMdd} then {@code _}/{@code -} then {@code HHmmss}. */
	static final Pattern DATE8_SEP_TIME6 = Pattern.compile(".*?(\\d{8})[_-](\\d{6}).*", Pattern.CASE_INSENSITIVE);

	/** Bare {@code yyyyMMdd} block anywhere in the name. */
	static final Pattern DATE8 = Pattern.compile(".*?(\\d{8}).*", Pattern.CASE_INSENSITIVE);

	private FileNameDatePatterns() {
	}
}