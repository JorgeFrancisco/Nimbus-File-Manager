package br.com.jorgemelo.nimbusfilemanager.backup.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * One number about one file, which is all the backup domain needs to know
 * whether what arrived is what left.
 *
 * <p>
 * Its own class for two reasons, and neither is ceremony. The first is that it
 * keeps the digest out of the metadata domain's hashing service: reaching
 * across for a single SHA-256 would put the backup back into the coupling the
 * mutation port was created to remove. The second is that a delivery which
 * discards a corrupted backup can only be proven by a test if the two readings
 * can disagree, and they never disagree by accident - a file does not corrupt
 * itself between two reads on demand.
 */
@Component
class BackupDigest {

	private static final int BUFFER_SIZE = 1024 * 1024;

	private static final String ALGORITHM = "SHA-256";

	String of(Path file) throws IOException {
		MessageDigest digest = messageDigest();

		try (InputStream content = Files.newInputStream(file)) {
			byte[] buffer = new byte[BUFFER_SIZE];

			int read;

			while ((read = content.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
		}

		return HexFormat.of().formatHex(digest.digest());
	}

	private MessageDigest messageDigest() {
		try {
			return MessageDigest.getInstance(ALGORITHM);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(ALGORITHM + " is required to verify a delivered backup", exception);
		}
	}
}