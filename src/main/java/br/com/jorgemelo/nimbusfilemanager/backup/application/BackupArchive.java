package br.com.jorgemelo.nimbusfilemanager.backup.application;

import static br.com.jorgemelo.nimbusfilemanager.backup.application.constants.BackupEntries.NAMES;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Component;

/**
 * Reading the finished archive back before it is kept, which is the only moment
 * anybody is in a position to take it again.
 *
 * <p>
 * The dump is already checked before it is packed, and the delivery proves that
 * what arrived is what left. Between the two sat the packing itself, whose
 * output nobody looked at until the day of the rescue - so the guarantee this
 * closes is "the artefact I produced is intact", which is a different question
 * from "the artefact I delivered is the one I validated".
 *
 * <p>
 * Two passes, because the JDK's two zip APIs each miss what the other catches,
 * measured rather than assumed:
 *
 * <ul>
 * <li>{@link ZipFile} reads the central directory, so it refuses an archive
 * whose tail was lost - and that is the API the restore itself opens, so what
 * it accepts here is exactly what the restore will accept later. It does
 * <em>not</em> confront the stored CRC-32: a stored entry with a flipped byte
 * reads through it without an error.</li>
 * <li>{@link ZipInputStream} walks the local headers and compares the stored
 * CRC-32 with what it computed once an entry is read to its end, which is the
 * check that catches a byte that changed after the fact. It never reads the
 * central directory, so on its own it accepts a truncated tail.</li>
 * </ul>
 *
 * <p>
 * Both passes also name what they saw, so the two indexes of the archive have
 * to agree with each other and with the format.
 */
@Component
class BackupArchive {

	/**
	 * @throws IllegalStateException when the archive is not one this product could
	 * restore. Deliberately not an {@link IOException}: the caller turns a failure
	 * to <em>write</em> into a message about the destination, and nothing has been
	 * written to the destination at this point - the archive is still in staging
	 * and the destination must be left exactly as it was.
	 */
	void verify(Path archive) {
		Set<String> indexed = readCentralDirectory(archive);

		Set<String> streamed = readEveryEntry(archive);

		require(indexed.equals(NAMES), archive, "its index lists " + indexed + " instead of " + NAMES);
		require(streamed.equals(NAMES), archive, "it holds " + streamed + " instead of " + NAMES);
	}

	/**
	 * What the archive says it contains, read the way the restore will read it. An
	 * archive that lost its tail fails here, with the data still intact and
	 * unreachable - which is the shape a half-written file takes.
	 */
	private Set<String> readCentralDirectory(Path archive) {
		Set<String> names = new HashSet<>();

		try (ZipFile zip = new ZipFile(archive.toFile())) {
			var entries = zip.entries();

			while (entries.hasMoreElements()) {
				accept(archive, names, entries.nextElement());
			}
		} catch (IOException exception) {
			throw failure(archive, "its index could not be read", exception);
		}

		return names;
	}

	/**
	 * Every byte of every entry, to its end. Reaching the end is the point: that
	 * is when the CRC-32 the archive stored is compared with the one just
	 * computed, and a byte that changed since the packing is found here or on the
	 * day of the rescue.
	 */
	private Set<String> readEveryEntry(Path archive) {
		Set<String> names = new HashSet<>();

		try (InputStream file = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(file)) {
			ZipEntry entry;

			while ((entry = zip.getNextEntry()) != null) {
				accept(archive, names, entry);

				drain(zip);

				zip.closeEntry();
			}
		} catch (IOException exception) {
			throw failure(archive, "it could not be read back in full", exception);
		}

		return names;
	}

	/**
	 * The whitelist, which is what keeps this from becoming a zip-security essay:
	 * the format admits two names, so a folder, a nested path, a traversal and an
	 * entry nobody expected are all refused by the same question.
	 */
	private void accept(Path archive, Set<String> names, ZipEntry entry) {
		String name = entry.getName();

		require(!entry.isDirectory(), archive, "it carries a folder entry: " + name);
		require(NAMES.contains(name), archive, "it carries an entry that does not belong: " + name);
		require(names.add(name), archive, "it carries " + name + " twice");
	}

	/**
	 * Every byte to the end and none of them kept: reaching the end is what makes
	 * the reader compare the CRC-32 the entry declared with the one it computed on
	 * the way.
	 */
	private void drain(InputStream data) throws IOException {
		data.transferTo(OutputStream.nullOutputStream());
	}

	private void require(boolean condition, Path archive, String because) {
		if (!condition) {
			throw failure(archive, because, null);
		}
	}

	private IllegalStateException failure(Path archive, String because, Exception cause) {
		return new IllegalStateException(
				"The backup " + archive.getFileName() + " was discarded because " + because, cause);
	}
}