package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What stands between a download and an installer that will run elevated. The
 * failure worth catching is not the exotic one - it is the connection dropped
 * at 90%, which leaves a file that exists, looks plausible and installs
 * nothing.
 */
class ChecksumsTest {

	/** The published SHA-256 of the empty input, from the standard itself. */
	private static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

	@Test
	void hashesAFileAsTheStandardSays(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("empty.msi"));

		Assertions.assertThat(Checksums.of(file)).isEqualTo(EMPTY);
	}

	/**
	 * Larger than the read buffer, so a hash computed only from the first chunk
	 * would disagree - which is the bug that would let a truncated download pass.
	 */
	@Test
	void hashesEveryByteOfAFileLargerThanItsBuffer(@TempDir Path folder) throws IOException {
		Path file = folder.resolve("big.msi");

		byte[] content = new byte[200_000];

		for (int index = 0; index < content.length; index++) {
			content[index] = (byte) index;
		}

		Files.write(file, content);

		Path truncated = folder.resolve("truncated.msi");

		Files.write(truncated, Arrays.copyOf(content, content.length - 1));

		Assertions.assertThat(Checksums.of(file)).isNotEqualTo(Checksums.of(truncated));
	}

	@Test
	void readsTheHashOutOfAPublishedChecksumFile() {
		Assertions.assertThat(Checksums.published(EMPTY + "  Nimbus.File.Manager-6.1.0.msi")).contains(EMPTY);
	}

	@Test
	void readsAHashThatStandsAlone() {
		Assertions.assertThat(Checksums.published(EMPTY)).contains(EMPTY);
		Assertions.assertThat(Checksums.published("  " + EMPTY + "\n")).contains(EMPTY);
	}

	@Test
	void readsAnUppercaseHashAsLowercase() {
		Assertions.assertThat(Checksums.published(EMPTY.toUpperCase(Locale.ROOT) + "  a.msi"))
				.contains(EMPTY);
	}

	/**
	 * An error page, an empty download or a different format declares no hash, and
	 * the only safe reading of that is none at all.
	 */
	@Test
	void findsNoHashInWhatIsNotOne() {
		Assertions.assertThat(Checksums.published(null)).isEmpty();
		Assertions.assertThat(Checksums.published("")).isEmpty();
		Assertions.assertThat(Checksums.published("   ")).isEmpty();
		Assertions.assertThat(Checksums.published("<html>404</html>")).isEmpty();
		Assertions.assertThat(Checksums.published("abc123  a.msi")).isEmpty();
		Assertions.assertThat(Checksums.published(EMPTY.substring(1) + "  a.msi")).isEmpty();
		Assertions.assertThat(Checksums.published(EMPTY + "0  a.msi")).isEmpty();
	}

	@Test
	void matchesIgnoringCase() {
		Assertions.assertThat(Checksums.matches(EMPTY, EMPTY.toUpperCase(Locale.ROOT))).isTrue();
	}

	@Test
	void refusesToMatchWhenEitherSideIsMissing() {
		Assertions.assertThat(Checksums.matches(null, EMPTY)).isFalse();
		Assertions.assertThat(Checksums.matches(EMPTY, null)).isFalse();
		Assertions.assertThat(Checksums.matches(EMPTY, "0".repeat(64))).isFalse();
	}
}