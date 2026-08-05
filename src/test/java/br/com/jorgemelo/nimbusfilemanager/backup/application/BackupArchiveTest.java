package br.com.jorgemelo.nimbusfilemanager.backup.application;

import static br.com.jorgemelo.nimbusfilemanager.backup.application.constants.BackupEntries.DUMP;
import static br.com.jorgemelo.nimbusfilemanager.backup.application.constants.BackupEntries.MANIFEST;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading the finished archive back, which is what nobody was doing between the
 * dump being checked and the file being kept.
 *
 * <p>
 * The two passes are here because the JDK's two zip APIs each miss what the
 * other catches, and that was measured rather than assumed: {@code ZipFile}
 * reads a stored entry with a flipped byte without complaining, and
 * {@code ZipInputStream} reads an archive whose tail was cut without
 * complaining. Two of the tests below are exactly those cases, so a future
 * simplification into a single pass fails here instead of on the day of a
 * rescue.
 */
class BackupArchiveTest {

	private static final byte[] JSON = "{\"schemaVersion\":\"21\"}".getBytes(StandardCharsets.UTF_8);

	private final BackupArchive archive = new BackupArchive();

	@Test
	void acceptsAnArchiveHoldingExactlyTheTwoEntriesOfTheFormat(@TempDir Path folder) throws IOException {
		Path file = deflated(folder.resolve("backup.zip"), dumpBytes(), JSON);

		Assertions.assertThatCode(() -> archive.verify(file)).doesNotThrowAnyException();
	}

	/**
	 * The archive that lost its tail: every byte of both entries is there and
	 * readable, and the index that says where they are is gone. The restore opens
	 * backups with {@code ZipFile}, so what this refuses is precisely what the
	 * restore would refuse - months later, with no way to take it again.
	 */
	@Test
	void refusesAnArchiveWhoseIndexWasLost(@TempDir Path folder) throws IOException {
		Path file = deflated(folder.resolve("backup.zip"), dumpBytes(), JSON);

		byte[] whole = Files.readAllBytes(file);

		Files.write(file, Arrays.copyOf(whole, whole.length - 40));

		Assertions.assertThatThrownBy(() -> archive.verify(file)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("index could not be read");
	}

	/**
	 * The case the CRC exists for: the archive is structurally perfect - it opens,
	 * both entries are listed, both are the declared length - and one byte of the
	 * dump is not what was written. Stored rather than deflated on purpose: with
	 * compression, a flipped byte usually breaks the inflater, and then the test
	 * would be proving that zlib works instead of that the checksum is confronted.
	 */
	@Test
	void refusesAnArchiveWhoseEntryBytesChangedAfterItWasWritten(@TempDir Path folder) throws IOException {
		byte[] dump = dumpBytes();

		Path file = stored(folder.resolve("backup.zip"), dump, JSON);

		byte[] whole = Files.readAllBytes(file);

		int at = indexOf(whole, dump) + dump.length / 2;

		whole[at] = (byte) (whole[at] ^ 0x5A);

		Files.write(file, whole);

		Assertions.assertThatThrownBy(() -> archive.verify(file)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("could not be read back in full").rootCause()
				.isInstanceOf(ZipException.class)
				// Named because it is the evidence this test carries: the rejection came
				// from the checksum the archive stores, not from a reader that stumbled.
				.hasMessageContaining("CRC");
	}

	/** The same corruption in the shape the packing actually produces. */
	@Test
	void refusesADeflatedEntryWhoseCompressedBytesChanged(@TempDir Path folder) throws IOException {
		Path file = deflated(folder.resolve("backup.zip"), dumpBytes(), JSON);

		byte[] whole = Files.readAllBytes(file);

		int at = 40 + (whole.length - 40) / 4;

		whole[at] = (byte) (whole[at] ^ 0x5A);

		Files.write(file, whole);

		Assertions.assertThatThrownBy(() -> archive.verify(file)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void refusesAnArchiveWithoutTheDump(@TempDir Path folder) throws IOException {
		Path file = deflated(folder.resolve("backup.zip"), null, JSON);

		Assertions.assertThatThrownBy(() -> archive.verify(file)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(DUMP);
	}

	@Test
	void refusesAnArchiveWithoutTheManifest(@TempDir Path folder) throws IOException {
		Path file = deflated(folder.resolve("backup.zip"), dumpBytes(), null);

		Assertions.assertThatThrownBy(() -> archive.verify(file)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(MANIFEST);
	}

	/**
	 * The whitelist doing the work a page of zip-security rules would otherwise
	 * do: the format has two names, so anything else is refused without this class
	 * having to reason about what the extra entry might be.
	 */
	@Test
	void refusesAnEntryThatDoesNotBelongToTheFormat(@TempDir Path folder) throws IOException {
		Path file = folder.resolve("backup.zip");

		try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
			write(zip, DUMP, dumpBytes());
			write(zip, MANIFEST, JSON);
			write(zip, "../escaped.txt", "somewhere else".getBytes(StandardCharsets.UTF_8));
		}

		Assertions.assertThatThrownBy(() -> archive.verify(file)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("does not belong").hasMessageContaining("escaped.txt");
	}

	@Test
	void refusesAFolderEntry(@TempDir Path folder) throws IOException {
		Path file = folder.resolve("backup.zip");

		try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("nested/"));
			zip.closeEntry();

			write(zip, DUMP, dumpBytes());
			write(zip, MANIFEST, JSON);
		}

		Assertions.assertThatThrownBy(() -> archive.verify(file)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("folder entry");
	}

	/**
	 * Two entries of the same name is the ambiguity every zip reader resolves
	 * differently - and a backup whose meaning depends on which reader opens it is
	 * not a backup.
	 */
	@Test
	void refusesTheSameEntryTwice(@TempDir Path folder) throws IOException {
		Path file = withDuplicateManifest(folder);

		Assertions.assertThatThrownBy(() -> archive.verify(file)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("twice");
	}

	@Test
	void refusesSomethingThatIsNotAnArchiveAtAll(@TempDir Path folder) throws IOException {
		Path file = Files.writeString(folder.resolve("backup.zip"), "this is not a zip");

		Assertions.assertThatThrownBy(() -> archive.verify(file)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("index could not be read");
	}

	@Test
	void refusesAnArchiveThatIsNotThere(@TempDir Path folder) {
		Path missing = folder.resolve("never-written.zip");

		Assertions.assertThatThrownBy(() -> archive.verify(missing)).isInstanceOf(IllegalStateException.class);
	}

	/**
	 * A structurally perfect archive carrying the manifest twice. Written under a
	 * decoy name of the same length and renamed byte for byte afterwards, because
	 * {@code ZipOutputStream} refuses to write the duplicate itself - and a reader
	 * that meets one has to decide which of the two it means, which is the
	 * ambiguity being refused.
	 */
	private Path withDuplicateManifest(Path folder) throws IOException {
		Path file = folder.resolve("backup.zip");

		String decoy = "manifesz.json";

		try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
			write(zip, DUMP, dumpBytes());
			write(zip, MANIFEST, JSON);
			write(zip, decoy, JSON);
		}

		byte[] whole = Files.readAllBytes(file);

		byte[] from = decoy.getBytes(StandardCharsets.UTF_8);
		byte[] to = MANIFEST.getBytes(StandardCharsets.UTF_8);

		for (int at = indexOf(whole, from); at >= 0; at = indexOf(whole, from)) {
			System.arraycopy(to, 0, whole, at, to.length);
		}

		Files.write(file, whole);

		return file;
	}

	private Path deflated(Path file, byte[] dump, byte[] manifest) throws IOException {
		try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
			if (dump != null) {
				write(zip, DUMP, dump);
			}

			if (manifest != null) {
				write(zip, MANIFEST, manifest);
			}
		}

		return file;
	}

	/**
	 * Stored entries carry their bytes literally, so nothing but the checksum can
	 * tell that one of them changed.
	 */
	private Path stored(Path file, byte[] dump, byte[] manifest) throws IOException {
		try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.setMethod(ZipOutputStream.STORED);

			writeStored(zip, DUMP, dump);
			writeStored(zip, MANIFEST, manifest);
		}

		return file;
	}

	private void write(ZipOutputStream zip, String name, byte[] content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content);
		zip.closeEntry();
	}

	private void writeStored(ZipOutputStream zip, String name, byte[] content) throws IOException {
		CRC32 crc = new CRC32();

		crc.update(content);

		ZipEntry entry = new ZipEntry(name);

		entry.setSize(content.length);
		entry.setCompressedSize(content.length);
		entry.setCrc(crc.getValue());

		zip.putNextEntry(entry);
		zip.write(content);
		zip.closeEntry();
	}

	/** Long enough that a flipped byte lands well inside the entry's data. */
	private byte[] dumpBytes() {
		return "PGDMP the catalog, in bytes that repeat so deflate has something to chew on. ".repeat(64)
				.getBytes(StandardCharsets.UTF_8);
	}

	private int indexOf(byte[] haystack, byte[] needle) {
		int compared = Math.min(8, needle.length);

		for (int start = 0; start <= haystack.length - needle.length; start++) {
			boolean found = true;

			for (int offset = 0; offset < compared; offset++) {
				if (haystack[start + offset] != needle[offset]) {
					found = false;

					break;
				}
			}

			if (found) {
				return start;
			}
		}

		return -1;
	}
}