package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileHashServiceTest {

	@TempDir
	Path tempDir;

	private final FileHashService service = new FileHashService();

	@Test
	void shouldCalculateKnownHashes() throws Exception {
		Path file = Files.writeString(tempDir.resolve("file.txt"), "abc");

		Assertions.assertThat(service.sha256(file))
				.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

	/**
	 * A read that dies mid-file must name the file: this runs over a whole library,
	 * and "could not read" without a path is not something anyone can act on.
	 */
	@Test
	void shouldNameTheFileWhenReadingItFails() throws Exception {
		Path file = Files.writeString(tempDir.resolve("unreadable.txt"), "abc");

		FileHashService failing = new FileHashService(_ -> {
			throw new IOException("drive went away");
		});

		assertThatIllegalStateException().isThrownBy(() -> failing.sha256(file)).withMessageContaining("unreadable.txt")
				.withMessageContaining("drive went away");
	}

	/** A JVM without SHA-256 is not something to guess about; it is reported. */
	@Test
	void shouldReportAMissingHashAlgorithm() throws Exception {
		Path file = Files.writeString(tempDir.resolve("file.txt"), "abc");

		FileHashService failing = new FileHashService(
				_ -> new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), algorithm -> {
					throw new NoSuchAlgorithmException(algorithm + " missing");
				});

		// The failure that explains it is chained, not spliced into the text: the
		// message says which algorithm was asked for, and the cause says what the
		// platform answered.
		assertThatIllegalStateException().isThrownBy(() -> failing.sha256(file))
				.withMessageContaining("Hash algorithm not available: SHA-256")
				.withCauseInstanceOf(NoSuchAlgorithmException.class)
				.havingCause().withMessageContaining("SHA-256 missing");
	}

	@Test
	void shouldValidateFileBeforeHashing() {
		assertThatIllegalArgumentException().isThrownBy(() -> service.sha256(tempDir.resolve("missing.txt")))
				.withMessageContaining("File does not exist");

		assertThatIllegalArgumentException().isThrownBy(() -> service.sha256(tempDir))
				.withMessageContaining("Path is not a regular file");
	}

	@Test
	void shouldWrapReadFailures() throws Exception {
		Path file = Files.writeString(tempDir.resolve("file.txt"), "abc");

		FileHashService failing = new FileHashService(_ -> {
			throw new IOException("locked");
		});

		Assertions.assertThatThrownBy(() -> failing.sha256(file)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Could not read file to calculate hash").hasMessageContaining("locked");
	}

	@Test
	void shouldWrapUnavailableHashAlgorithm() throws Exception {
		Path file = Files.writeString(tempDir.resolve("file.txt"), "abc");

		FileHashService failing = new FileHashService(Files::newInputStream, _ -> {
			throw new NoSuchAlgorithmException("missing");
		});

		Assertions.assertThatThrownBy(() -> failing.sha256(file)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Hash algorithm not available")
				.hasRootCauseInstanceOf(NoSuchAlgorithmException.class);
	}
}