package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Rebuilds the first frame of an animated WebP as a plain WebP image.
 *
 * <p>
 * ffmpeg's WebP decoder does not read animation: an animated sticker reports
 * "image data not found" and zero dimensions, so the whole file looked
 * undecodable and every WhatsApp sticker in the library stayed without a
 * fingerprint. The frames themselves are ordinary WebP images - each
 * {@code ANMF} chunk carries a normal {@code VP8}/{@code VP8L} payload after a
 * 16-byte header - so lifting the first one into a minimal container gives the
 * decoder something it has always understood, with no new dependency.
 *
 * <p>
 * The first frame is the right one to read here: the caller wants a perceptual
 * hash, and a sticker's opening frame is what represents it.
 */
public final class AnimatedWebp {

	private static final int RIFF_HEADER_BYTES = 12;

	private static final int CHUNK_HEADER_BYTES = 8;

	/** Frame x/y, width/height, duration and flags, before the image payload. */
	private static final int ANMF_HEADER_BYTES = 16;

	private static final byte[] RIFF = { 'R', 'I', 'F', 'F' };

	private static final byte[] WEBP = { 'W', 'E', 'B', 'P' };

	private static final String ANMF = "ANMF";

	private static final String VP8 = "VP8 ";

	private static final String VP8L = "VP8L";

	/**
	 * @return the first frame as a standalone WebP image, or an empty array when
	 *         {@code file} is not an animated WebP - the caller then decodes the
	 *         original, which is what every other image needs.
	 */
	public static byte[] firstFrame(Path file) throws IOException {
		byte[] bytes = Files.readAllBytes(file);

		if (!isWebpContainer(bytes)) {
			return new byte[0];
		}

		byte[] frame = findFirstFrameImage(bytes);

		return frame.length == 0 ? new byte[0] : wrapAsWebp(frame);
	}

	private static boolean isWebpContainer(byte[] bytes) {
		return bytes.length > RIFF_HEADER_BYTES && matches(bytes, 0, RIFF) && matches(bytes, 8, WEBP);
	}

	/** Walks the top-level chunks and returns the first frame's image payload. */
	private static byte[] findFirstFrameImage(byte[] bytes) {
		int position = RIFF_HEADER_BYTES;

		while (position + CHUNK_HEADER_BYTES <= bytes.length) {
			String tag = tagAt(bytes, position);
			int size = sizeAt(bytes, position);

			if (size < 0 || position + CHUNK_HEADER_BYTES + size > bytes.length) {
				return new byte[0];
			}

			if (ANMF.equals(tag)) {
				return imageInsideFrame(bytes, position + CHUNK_HEADER_BYTES + ANMF_HEADER_BYTES,
						position + CHUNK_HEADER_BYTES + size);
			}

			position += CHUNK_HEADER_BYTES + size + (size & 1);
		}

		return new byte[0];
	}

	/**
	 * The frame's own chunks. Only the image payload is taken: the alpha chunk
	 * beside it needs the extended container to be meaningful, and a hash is
	 * computed on luminance anyway.
	 */
	private static byte[] imageInsideFrame(byte[] bytes, int from, int to) {
		int position = from;

		while (position + CHUNK_HEADER_BYTES <= to) {
			String tag = tagAt(bytes, position);
			int size = sizeAt(bytes, position);

			if (size < 0 || position + CHUNK_HEADER_BYTES + size > to) {
				return new byte[0];
			}

			if (VP8.equals(tag) || VP8L.equals(tag)) {
				int length = CHUNK_HEADER_BYTES + size + (size & 1);

				byte[] chunk = new byte[Math.min(length, bytes.length - position)];

				System.arraycopy(bytes, position, chunk, 0, chunk.length);

				return chunk;
			}

			position += CHUNK_HEADER_BYTES + size + (size & 1);
		}

		return new byte[0];
	}

	private static byte[] wrapAsWebp(byte[] imageChunk) throws IOException {
		ByteArrayOutputStream webp = new ByteArrayOutputStream(imageChunk.length + RIFF_HEADER_BYTES);

		webp.write(RIFF);
		webp.write(littleEndian(WEBP.length + imageChunk.length));
		webp.write(WEBP);
		webp.write(imageChunk);

		return webp.toByteArray();
	}

	private static byte[] littleEndian(int value) {
		return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
	}

	private static String tagAt(byte[] bytes, int position) {
		return new String(bytes, position, 4, StandardCharsets.ISO_8859_1);
	}

	private static int sizeAt(byte[] bytes, int position) {
		return ByteBuffer.wrap(bytes, position + 4, Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).getInt();
	}

	private static boolean matches(byte[] bytes, int offset, byte[] expected) {
		for (int index = 0; index < expected.length; index++) {
			if (bytes[offset + index] != expected[index]) {
				return false;
			}
		}

		return true;
	}

	private AnimatedWebp() {
	}
}