package br.com.jorgemelo.nimbusfilemanager.backup.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.springframework.stereotype.Component;

/**
 * Putting the finished backup where the user keeps their backups, and proving
 * the bytes arrived.
 *
 * <p>
 * The destination is a folder this application declares its own: the scan
 * exclusion covers it, and the folder watcher drops every change under it
 * before anything else is asked - deliberately, because people put it on a
 * synchronised drive inside the watched library, and a backup landing there
 * would otherwise read as hundreds of megabytes of new files arriving. So this
 * is not a change to the user's library and does not go through the library
 * port: the artefact is the application's own output, kept where the
 * application was told to keep it.
 *
 * <p>
 * What the port did contribute here, and is kept, is the verification. When the
 * destination is another disk - which is the whole point of taking a backup -
 * the move is a copy, and nothing else in the system would notice a copy that
 * arrived wrong. The digest is taken before and after, and a mismatch deletes
 * what was written rather than leaving a file that only looks like a rescue.
 */
@Component
class BackupDelivery {

	private final BackupDigest backupDigest;

	BackupDelivery(BackupDigest backupDigest) {
		this.backupDigest = backupDigest;
	}

	/**
	 * @throws IOException when the file could not be written, or when what arrived
	 * is not what left
	 */
	void deliver(Path artifact, Path target) throws IOException {
		String expected = backupDigest.of(artifact);

		Path parent = target.getParent();

		if (parent != null) {
			Files.createDirectories(parent);
		}

		Files.move(artifact, target, StandardCopyOption.REPLACE_EXISTING);

		String delivered = backupDigest.of(target);

		if (!expected.equals(delivered)) {
			Files.deleteIfExists(target);

			throw new IOException("The backup did not arrive intact at " + target + " and was discarded: expected "
					+ expected + ", got " + delivered);
		}
	}
}