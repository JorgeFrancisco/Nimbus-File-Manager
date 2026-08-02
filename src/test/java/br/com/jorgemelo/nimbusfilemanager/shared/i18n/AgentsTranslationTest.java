package br.com.jorgemelo.nimbusfilemanager.shared.i18n;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Keeps the English translation of the rules in step with the rules.
 *
 * <p>
 * {@code AGENTS.md} is normative and {@code AGENTS.en.md} is a courtesy
 * translation, which is the arrangement that stops the instruction context from
 * carrying every rule twice. What that arrangement cannot do on its own is stay
 * true: a rule changed in one file and not the other leaves two versions of the
 * same policy, and a reader has no way of telling which one is current.
 *
 * <p>
 * So the translation records the hash of the original it was written from, and
 * this test recomputes it. The same idea the message bundles already use -
 * parity enforced by the build rather than by memory - applied to the document
 * that describes the rules.
 */
class AgentsTranslationTest {

	private static final Path RULES = Path.of("AGENTS.md");
	private static final Path TRANSLATION = Path.of("AGENTS.en.md");

	private static final Pattern MARKER = Pattern.compile("<!-- agents-sha256: ([0-9a-f]{64}) -->");

	@Test
	void theTranslationDeclaresTheRulesItWasWrittenFrom() {
		Matcher marker = MARKER.matcher(read(TRANSLATION));

		Assertions.assertThat(marker.find())
				.withFailMessage("%s must carry the marker <!-- agents-sha256: … --> naming the %s it translates",
						TRANSLATION, RULES)
				.isTrue();

		Assertions.assertThat(marker.group(1)).withFailMessage("""
				%s changed and %s was not retranslated.

				Translate what changed, then replace the marker with:
				<!-- agents-sha256: %s -->""", RULES, TRANSLATION, sha256(RULES)).isEqualTo(sha256(RULES));
	}

	/**
	 * Both files are prose, so a heading that exists in one and not in the other is
	 * a section that was added or dropped without its counterpart - the coarse
	 * shape of the drift the hash then pins down precisely.
	 */
	@Test
	void bothDocumentsAreBuiltFromTheSameSections() {
		Assertions.assertThat(headings(TRANSLATION))
				.withFailMessage("%s has %d headings and %s has %d: a section was added or dropped on one side only",
						TRANSLATION, headings(TRANSLATION), RULES, headings(RULES))
				.isEqualTo(headings(RULES));
	}

	private long headings(Path file) {
		return read(file).lines().filter(line -> line.startsWith("#")).count();
	}

	/**
	 * Hashed with line endings normalised: {@code .gitattributes} keeps LF in the
	 * repository and hands CRLF to a Windows working tree, so hashing the raw bytes
	 * would pass on one machine and fail on the CI for no reason at all.
	 */
	private String sha256(Path file) {
		try {
			byte[] normalized = read(file).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);

			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by every JVM", e);
		}
	}

	private String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + file.toAbsolutePath(), e);
		}
	}
}