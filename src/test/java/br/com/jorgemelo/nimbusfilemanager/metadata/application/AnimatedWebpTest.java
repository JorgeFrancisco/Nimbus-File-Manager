package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shape checked here is the one a real WhatsApp sticker has: an extended
 * container flagged as animated, an {@code ANIM} chunk, and frames whose image
 * payload sits after a 16-byte header, beside an alpha chunk.
 */
class AnimatedWebpTest {

	private static final byte[] IMAGE_PAYLOAD = { 9, 8, 7, 6, 5, 4 };

	/** Frame x/y, width/height, duration and flags, before the image payload. */
	private static final int ANMF_HEADER_BYTES = 16;

	/** ffmpeg reads no animation, so the frame has to arrive as a plain image. */
	@Test
	void liftsTheFirstFrameIntoAPlainWebpContainer(@TempDir Path tmp) throws IOException {
		Path sticker = Files.write(tmp.resolve("sticker.webp"), animatedWebp());

		byte[] frame = AnimatedWebp.firstFrame(sticker);

		assertThat(new String(frame, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("RIFF");
		assertThat(new String(frame, 8, 4, StandardCharsets.ISO_8859_1)).isEqualTo("WEBP");
		assertThat(new String(frame, 12, 4, StandardCharsets.ISO_8859_1)).isEqualTo("VP8 ");
		assertThat(frame).endsWith(IMAGE_PAYLOAD);
	}

	/** The RIFF size field has to describe what follows, or no decoder reads it. */
	@Test
	void theRebuiltContainerDeclaresItsOwnLength(@TempDir Path tmp) throws IOException {
		Path sticker = Files.write(tmp.resolve("sticker.webp"), animatedWebp());

		byte[] frame = AnimatedWebp.firstFrame(sticker);

		int declared = ByteBuffer.wrap(frame, 4, Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).getInt();

		assertThat(declared).isEqualTo(frame.length - 8);
	}

	/**
	 * A still WebP already decodes, so nothing is lifted and the caller keeps
	 * reading the original file.
	 */
	@Test
	void aStillWebpIsLeftAlone(@TempDir Path tmp) throws IOException {
		Path still = Files.write(tmp.resolve("still.webp"), riff(chunk("VP8 ", IMAGE_PAYLOAD)));

		assertThat(AnimatedWebp.firstFrame(still)).isEmpty();
	}

	@Test
	void anythingThatIsNotAWebpIsLeftAlone(@TempDir Path tmp) throws IOException {
		Path jpeg = Files.write(tmp.resolve("photo.jpg"), new byte[] { (byte) 0xFF, (byte) 0xD8, 1, 2, 3, 4, 5, 6 });

		assertThat(AnimatedWebp.firstFrame(jpeg)).isEmpty();
	}

	@Test
	void aTruncatedFileIsLeftAloneInsteadOfReadingPastItsEnd(@TempDir Path tmp) throws IOException {
		byte[] animated = animatedWebp();

		Path cut = Files.write(tmp.resolve("cut.webp"), Arrays.copyOf(animated, animated.length / 2));

		assertThat(AnimatedWebp.firstFrame(cut)).isEmpty();
	}

	/** A frame carrying no image payload yields nothing rather than a broken file. */
	@Test
	void aFrameWithoutImageDataIsLeftAlone(@TempDir Path tmp) throws IOException {
		byte[] frameBody = concat(new byte[ANMF_HEADER_BYTES], chunk("ALPH", new byte[] { 1, 2 }));

		Path odd = Files.write(tmp.resolve("odd.webp"),
				riff(concat(chunk("VP8X", new byte[10]), chunk("ANMF", frameBody))));

		assertThat(AnimatedWebp.firstFrame(odd)).isEmpty();
	}

	/** Lossless frames are just as valid, and stickers use both. */
	@Test
	void aLosslessFrameIsLiftedTheSameWay(@TempDir Path tmp) throws IOException {
		byte[] frameBody = concat(new byte[ANMF_HEADER_BYTES], chunk("VP8L", IMAGE_PAYLOAD));

		Path sticker = Files.write(tmp.resolve("lossless.webp"),
				riff(concat(chunk("VP8X", new byte[10]), chunk("ANMF", frameBody))));

		byte[] frame = AnimatedWebp.firstFrame(sticker);

		assertThat(new String(frame, 12, 4, StandardCharsets.ISO_8859_1)).isEqualTo("VP8L");
	}

	/** Shorter than a container header: there is nothing to walk. */
	@Test
	void aFileTooShortToBeAContainerIsLeftAlone(@TempDir Path tmp) throws IOException {
		Path stub = Files.write(tmp.resolve("stub.webp"), "RIFF0000WEBP".getBytes(StandardCharsets.ISO_8859_1));

		assertThat(AnimatedWebp.firstFrame(stub)).isEmpty();
	}

	/**
	 * A chunk that claims more bytes than the file holds is refused instead of read
	 * past the end - the bytes come from outside and cannot be trusted to add up.
	 */
	@Test
	void aChunkClaimingMoreThanTheFileHoldsIsRefused(@TempDir Path tmp) throws IOException {
		byte[] lying = concat("ANMF".getBytes(StandardCharsets.ISO_8859_1), littleEndian(9_000), new byte[20]);

		Path broken = Files.write(tmp.resolve("broken.webp"), riff(lying));

		assertThat(AnimatedWebp.firstFrame(broken)).isEmpty();
	}

	/** The same distrust applies to the chunks inside a frame. */
	@Test
	void aFrameChunkClaimingMoreThanTheFrameHoldsIsRefused(@TempDir Path tmp) throws IOException {
		byte[] lying = concat(new byte[ANMF_HEADER_BYTES], "VP8 ".getBytes(StandardCharsets.ISO_8859_1),
				littleEndian(9_000), new byte[4]);

		Path broken = Files.write(tmp.resolve("broken-frame.webp"), riff(chunk("ANMF", lying)));

		assertThat(AnimatedWebp.firstFrame(broken)).isEmpty();
	}

	private static byte[] animatedWebp() throws IOException {
		byte[] frameBody = concat(new byte[ANMF_HEADER_BYTES], chunk("ALPH", new byte[] { 1, 2 }),
				chunk("VP8 ", IMAGE_PAYLOAD));

		return riff(concat(chunk("VP8X", new byte[10]), chunk("ANIM", new byte[6]), chunk("ANMF", frameBody)));
	}

	private static byte[] riff(byte[] body) throws IOException {
		ByteArrayOutputStream webp = new ByteArrayOutputStream();

		webp.write("RIFF".getBytes(StandardCharsets.ISO_8859_1));
		webp.write(littleEndian(4 + body.length));
		webp.write("WEBP".getBytes(StandardCharsets.ISO_8859_1));
		webp.write(body);

		return webp.toByteArray();
	}

	private static byte[] chunk(String tag, byte[] payload) throws IOException {
		ByteArrayOutputStream chunk = new ByteArrayOutputStream();

		chunk.write(tag.getBytes(StandardCharsets.ISO_8859_1));
		chunk.write(littleEndian(payload.length));
		chunk.write(payload);

		if ((payload.length & 1) == 1) {
			chunk.write(0);
		}

		return chunk.toByteArray();
	}

	private static byte[] concat(byte[]... parts) throws IOException {
		ByteArrayOutputStream all = new ByteArrayOutputStream();

		for (byte[] part : parts) {
			all.write(part);
		}

		return all.toByteArray();
	}

	private static byte[] littleEndian(int value) {
		return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
	}
}