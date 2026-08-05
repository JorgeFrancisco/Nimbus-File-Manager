package br.com.jorgemelo.nimbusfilemanager.backup.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one number the backup domain needs about a file, computed here rather
 * than fetched from the metadata domain's hashing service - which would put the
 * backup back into the coupling the mutation port was created to remove.
 */
class BackupDigestTest {

	private final BackupDigest digest = new BackupDigest();

	@Test
	void answersTheSameForTheSameBytesAndDifferentlyForDifferentOnes(@TempDir Path folder) throws IOException {
		Path first = Files.writeString(folder.resolve("a.zip"), "the backup");
		Path copy = Files.writeString(folder.resolve("b.zip"), "the backup");
		Path other = Files.writeString(folder.resolve("c.zip"), "the backup, almost");

		Assertions.assertThat(digest.of(first)).isEqualTo(digest.of(copy)).isNotEqualTo(digest.of(other));
	}

	/**
	 * Backups run to gigabytes, so the file is read in chunks - which is only worth
	 * asserting on content larger than one buffer, where a naive implementation
	 * would digest the first block and call it done.
	 */
	@Test
	void readsPastTheFirstBufferOfALargeFile(@TempDir Path folder) throws IOException {
		byte[] content = new byte[3 * 1024 * 1024];

		byte[] tampered = content.clone();

		tampered[content.length - 1] = 7;

		Path whole = Files.write(folder.resolve("whole.zip"), content);
		Path changedAtTheEnd = Files.write(folder.resolve("tail.zip"), tampered);

		Assertions.assertThat(digest.of(whole)).isNotEqualTo(digest.of(changedAtTheEnd));
	}

	@Test
	void failsWhenThereIsNoFileToRead(@TempDir Path folder) {
		Path missing = folder.resolve("gone.zip");

		Assertions.assertThatThrownBy(() -> digest.of(missing)).isInstanceOf(IOException.class);
	}
}