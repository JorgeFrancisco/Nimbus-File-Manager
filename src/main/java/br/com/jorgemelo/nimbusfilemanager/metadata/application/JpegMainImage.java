package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Cuts a JPEG loose from whatever a camera appended after it.
 *
 * <p>
 * Samsung panoramas carry the source frames of the panorama after the image
 * itself, in a vendor trailer ending in {@code SEFT} - in this library it is
 * 71% of those files. ffmpeg does not stop at the end of the image: it reads on
 * into the trailer, finds bytes that look like a Huffman table with impossible
 * lengths and gives up with "bits 173 is invalid". The photo was never damaged;
 * the decoder was reading past it.
 *
 * <p>
 * Nothing is rebuilt here, unlike {@link AnimatedWebp}: the start of the file
 * is already a complete JPEG, so the only thing needed is to stop where it
 * stops.
 */
public final class JpegMainImage {

	private static final int MARKER_BYTES = 2;

	private static final byte MARKER = (byte) 0xFF;

	private static final byte SOI = (byte) 0xD8;

	private static final byte EOI = (byte) 0xD9;

	private static final byte SOS = (byte) 0xDA;

	private static final byte STUFFING = 0x00;

	private static final byte FIRST_RESTART = (byte) 0xD0;

	private static final byte LAST_RESTART = (byte) 0xD7;

	/**
	 * @return the JPEG alone, without the trailer behind it, or an empty array when
	 * there is no trailer to drop - the caller then decodes the original, which is
	 * what an ordinary photo needs.
	 */
	public static byte[] withoutTrailer(Path file) throws IOException {
		byte[] bytes = Files.readAllBytes(file);

		if (!startsWithSoi(bytes)) {
			return new byte[0];
		}

		int scan = startOfScan(bytes);

		if (scan < 0) {
			return new byte[0];
		}

		int end = endOfImage(bytes, scan);

		// Nothing after the image: an ordinary photo, decoded from its own file.
		if (end < 0 || end >= bytes.length) {
			return new byte[0];
		}

		byte[] image = Arrays.copyOf(bytes, end);

		// The image may stop at the trailer without its own end marker; a decoder is
		// entitled to expect one, so the cut carries it.
		return endsWithEoi(image) ? image : withEoi(image);
	}

	private static boolean endsWithEoi(byte[] image) {
		return image.length >= MARKER_BYTES && image[image.length - 2] == MARKER && image[image.length - 1] == EOI;
	}

	private static byte[] withEoi(byte[] image) {
		byte[] closed = Arrays.copyOf(image, image.length + MARKER_BYTES);

		closed[image.length] = MARKER;
		closed[image.length + 1] = EOI;

		return closed;
	}

	private static boolean startsWithSoi(byte[] bytes) {
		return bytes.length > MARKER_BYTES && bytes[0] == MARKER && bytes[1] == SOI;
	}

	/** Walks the header segments and answers where the image data begins. */
	private static int startOfScan(byte[] bytes) {
		int position = MARKER_BYTES;

		while (position + 4 <= bytes.length) {
			if (bytes[position] != MARKER) {
				return -1;
			}

			byte marker = bytes[position + 1];

			if (marker == SOI || isRestart(marker)) {
				position += MARKER_BYTES;
				continue;
			}

			int length = segmentLength(bytes, position);

			if (length < MARKER_BYTES || position + MARKER_BYTES + length > bytes.length) {
				return -1;
			}

			if (marker == SOS) {
				return position + MARKER_BYTES + length;
			}

			position += MARKER_BYTES + length;
		}

		return -1;
	}

	/**
	 * Reads the entropy-coded data, where every {@code FF} belongs to the image
	 * unless it introduces a restart marker, and answers the first byte after the
	 * image. Anything else is where the image stopped and the trailer began.
	 */
	private static int endOfImage(byte[] bytes, int scan) {
		int position = scan;

		while (position + 1 < bytes.length) {
			if (bytes[position] != MARKER) {
				position++;
			} else {
				byte next = bytes[position + 1];

				if (next == EOI) {
					return position + MARKER_BYTES;
				}

				if (!belongsToTheImage(next)) {
					return position;
				}

				// A fill byte consumes only the first FF, so the byte after it is still
				// read as the start of a marker.
				position += next == MARKER ? 1 : MARKER_BYTES;
			}
		}

		return -1;
	}

	/**
	 * Whether an FF found in the image data still belongs to it: a stuffed byte, a
	 * fill byte or a restart marker. Anything else is where the trailer begins.
	 */
	private static boolean belongsToTheImage(byte next) {
		return next == STUFFING || next == MARKER || isRestart(next);
	}

	private static boolean isRestart(byte marker) {
		return marker >= FIRST_RESTART && marker <= LAST_RESTART;
	}

	private static int segmentLength(byte[] bytes, int position) {
		return ((bytes[position + 2] & 0xFF) << 8) | (bytes[position + 3] & 0xFF);
	}

	private JpegMainImage() {
	}
}