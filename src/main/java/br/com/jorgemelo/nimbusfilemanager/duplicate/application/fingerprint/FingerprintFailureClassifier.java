package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;

/**
 * Reads the file itself to say why it has no fingerprint.
 *
 * <p>
 * The decoder's own message cannot tell these apart - ffmpeg reports "invalid
 * data" for a sticker it never supported, for a photo whose bytes are all zero
 * and for a panorama carrying a vendor trailer. The bytes can: a header and the
 * last two bytes are enough, and both are cheap on a file that already failed.
 */
public final class FingerprintFailureClassifier {

	/** Enough to recognise every signature below, and to trust an all-zero head. */
	private static final int HEAD_BYTES = 512;

	private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8 };

	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	private static final byte[] GIF = { 'G', 'I', 'F', '8' };

	private static final byte[] BMP = { 'B', 'M' };

	private static final byte[] RIFF = { 'R', 'I', 'F', 'F' };

	private static final byte[] WEBP = { 'W', 'E', 'B', 'P' };

	private static final byte[] VP8X = { 'V', 'P', '8', 'X' };

	/**
	 * @return why {@code file} could not be fingerprinted, or {@link
	 *         FingerprintFailureReason#UNKNOWN} when the bytes say nothing - an
	 *         unreadable file included, since that may be a mount that comes back.
	 */
	public static FingerprintFailureReason classify(Path file) {
		byte[] head;

		try {
			head = head(file);
		} catch (IOException _) {
			return FingerprintFailureReason.UNKNOWN;
		}

		if (head.length == 0) {
			return FingerprintFailureReason.CORRUPTED_FILE;
		}

		if (allZero(head)) {
			// A real image never opens with hundreds of zero bytes. The whole file being
			// blank is what a lost sector or a half-written sync leaves behind.
			return FingerprintFailureReason.CORRUPTED_FILE;
		}

		if (startsWith(head, RIFF) && regionMatches(head, 8, WEBP)) {
			return regionMatches(head, 12, VP8X) ? FingerprintFailureReason.UNSUPPORTED_FORMAT
					: FingerprintFailureReason.DECODER_REFUSED;
		}

		// A real image the decoder still refused: a cut stream, or a vendor trailer
		// after the image data (Samsung panoramas end in SEFT). Both are terminal and
		// the bytes cannot tell them apart, so they share one honest reason.
		if (startsWith(head, JPEG) || startsWith(head, PNG) || startsWith(head, GIF) || startsWith(head, BMP)) {
			return FingerprintFailureReason.DECODER_REFUSED;
		}

		return FingerprintFailureReason.NOT_AN_IMAGE;
	}

	private static byte[] head(Path file) throws IOException {
		try (InputStream stream = Files.newInputStream(file)) {
			return stream.readNBytes(HEAD_BYTES);
		}
	}

	private static boolean allZero(byte[] bytes) {
		for (byte value : bytes) {
			if (value != 0) {
				return false;
			}
		}

		return true;
	}

	private static boolean startsWith(byte[] bytes, byte[] prefix) {
		return regionMatches(bytes, 0, prefix);
	}

	private static boolean regionMatches(byte[] bytes, int offset, byte[] expected) {
		if (bytes.length < offset + expected.length) {
			return false;
		}

		for (int index = 0; index < expected.length; index++) {
			if (bytes[offset + index] != expected[index]) {
				return false;
			}
		}

		return true;
	}

	private FingerprintFailureClassifier() {
	}
}