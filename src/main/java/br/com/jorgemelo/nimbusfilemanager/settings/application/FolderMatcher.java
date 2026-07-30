package br.com.jorgemelo.nimbusfilemanager.settings.application;

import java.util.regex.Pattern;

/**
 * One configured folder-exclusion pattern, ready to match: a literal name
 * matched case-insensitively, or a glob ({@code *}, {@code ?}) compiled once.
 * Single owner of the glob-to-regex conversion - the scanner and the
 * exclusion service both match here instead of each rebuilding the rule.
 */
public record FolderMatcher(String pattern, Pattern compiled) {

	public static FolderMatcher of(String pattern) {
		boolean wildcard = pattern.contains("*") || pattern.contains("?");

		return new FolderMatcher(pattern,
				wildcard ? Pattern.compile(globRegex(pattern), Pattern.CASE_INSENSITIVE) : null);
	}

	public boolean matches(String folderName) {
		return compiled == null ? folderName.equalsIgnoreCase(pattern) : compiled.matcher(folderName).matches();
	}

	private static String globRegex(String pattern) {
		StringBuilder regex = new StringBuilder();

		for (int index = 0; index < pattern.length(); index++) {
			char value = pattern.charAt(index);

			switch (value) {
			case '*' -> regex.append(".*");
			case '?' -> regex.append('.');
			default -> regex.append(Pattern.quote(String.valueOf(value)));
			}
		}

		return regex.toString();
	}
}