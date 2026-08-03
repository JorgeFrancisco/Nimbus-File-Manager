package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SHA-256 of a downloaded file, and the one that was published beside it.
 *
 * <p>
 * This is what stands between a release page and an installer that runs with
 * the privileges of whoever double clicks it. The bytes travel over a network
 * this project does not control, through whatever proxy or cache sits in the
 * way, and a truncated download is far more likely than a malicious one - a
 * connection dropped at 90% leaves a file that exists, has a plausible size and
 * installs nothing. Both cases are caught by the same comparison.
 *
 * <p>
 * The published file is the output of a checksum tool: the hash, whitespace,
 * then the file name. Only the hash is read from it, and only when it is
 * exactly sixty-four hexadecimal characters - a file that is an error page, an
 * empty download or a different format has no hash to offer, and saying so is
 * the only safe answer.
 */
public final class Checksums {

	private static final Pattern SHA256 = Pattern.compile("([0-9a-fA-F]{64})(?:\\s.*)?", Pattern.DOTALL);
	private static final int BUFFER = 64 * 1024;

	private Checksums() {
	}

	/** The hash of what was actually downloaded, in lowercase hexadecimal. */
	public static String of(Path file) throws IOException {
		MessageDigest digest = sha256();

		try (InputStream source = Files.newInputStream(file)) {
			byte[] buffer = new byte[BUFFER];

			int read;

			while ((read = source.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
			}
		}

		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * @param text the contents of the published checksum file
	 * @return the hash it declares, or empty when it declares none
	 */
	public static Optional<String> published(String text) {
		if (text == null || text.isBlank()) {
			return Optional.empty();
		}

		Matcher matcher = SHA256.matcher(text.trim());

		return matcher.matches() ? Optional.of(matcher.group(1).toLowerCase(Locale.ROOT)) : Optional.empty();
	}

	/**
	 * Case-insensitive because the two sides are written by different tools, and
	 * only ever true for two hashes that are both present.
	 */
	public static boolean matches(String actual, String expected) {
		return actual != null && expected != null && actual.equalsIgnoreCase(expected);
	}

	/**
	 * SHA-256 is required of every Java platform, so its absence is not a case a
	 * caller could handle.
	 */
	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}
}