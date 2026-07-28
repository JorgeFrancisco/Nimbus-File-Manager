package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;

/**
 * The decoder reports "invalid data" for every one of these, which is why the
 * bytes have to answer instead. The cases mirror what a real library holds: a
 * blank photo, a sticker, a panorama with a vendor trailer and a saved web page
 * wearing an image extension.
 */
class FingerprintFailureClassifierTest {

	/**
	 * The one reason that means the user lost data rather than met a limitation:
	 * the file kept its size in the directory and nothing inside it.
	 */
	@Test
	void aFileOfNothingButZerosIsCorrupted(@TempDir Path tmp) throws IOException {
		Path blank = Files.write(tmp.resolve("photo.jpg"), new byte[4096]);

		assertThat(FingerprintFailureClassifier.classify(blank)).isEqualTo(FingerprintFailureReason.CORRUPTED_FILE);
	}

	@Test
	void anEmptyFileIsCorruptedToo(@TempDir Path tmp) throws IOException {
		Path empty = Files.createFile(tmp.resolve("empty.jpg"));

		assertThat(FingerprintFailureClassifier.classify(empty)).isEqualTo(FingerprintFailureReason.CORRUPTED_FILE);
	}

	/** Extended WebP is what animated stickers use, and the decoder reads none. */
	@Test
	void anExtendedWebpIsAnUnsupportedFormat(@TempDir Path tmp) throws IOException {
		Path sticker = Files.write(tmp.resolve("sticker.webp"), riff("VP8X"));

		assertThat(FingerprintFailureClassifier.classify(sticker))
				.isEqualTo(FingerprintFailureReason.UNSUPPORTED_FORMAT);
	}

	@Test
	void aPlainWebpIsAStreamTheDecoderRefused(@TempDir Path tmp) throws IOException {
		Path still = Files.write(tmp.resolve("still.webp"), riff("VP8 "));

		assertThat(FingerprintFailureClassifier.classify(still)).isEqualTo(FingerprintFailureReason.DECODER_REFUSED);
	}

	/**
	 * A real photo the decoder still rejects - a cut stream, or the vendor trailer
	 * Samsung panoramas carry after the image data.
	 */
	@Test
	void aJpegTheDecoderRejectsIsARefusedStream(@TempDir Path tmp) throws IOException {
		Path panorama = Files.write(tmp.resolve("panorama.jpg"),
				new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 1, 2, 3, 'S', 'E', 'F', 'T' });

		assertThat(FingerprintFailureClassifier.classify(panorama)).isEqualTo(FingerprintFailureReason.DECODER_REFUSED);
	}

	/** A saved web page kept the extension of the image it replaced. */
	@Test
	void contentThatIsNotAnImageIsSaidToBeSo(@TempDir Path tmp) throws IOException {
		Path page = Files.writeString(tmp.resolve("thumbnail.jpg"), "<html><head><title>página</title></head>");

		assertThat(FingerprintFailureClassifier.classify(page)).isEqualTo(FingerprintFailureReason.NOT_AN_IMAGE);
	}

	/**
	 * An unreadable file stays retryable: a drive that was not mounted comes back,
	 * and calling it corrupted would accuse the user of a loss that never happened.
	 */
	@Test
	void aFileThatCannotBeReadIsLeftUnclassified(@TempDir Path tmp) {
		assertThat(FingerprintFailureClassifier.classify(tmp.resolve("gone.jpg")))
				.isEqualTo(FingerprintFailureReason.UNKNOWN);
	}

	/** RIFF is a container: without the WEBP tag it is audio, not a picture. */
	@Test
	void aRiffThatIsNotWebpIsNotAnImage(@TempDir Path tmp) throws IOException {
		byte[] wave = new byte[32];

		System.arraycopy("RIFF".getBytes(), 0, wave, 0, 4);
		System.arraycopy("WAVE".getBytes(), 0, wave, 8, 4);

		Path audio = Files.write(tmp.resolve("sound.webp"), wave);

		assertThat(FingerprintFailureClassifier.classify(audio)).isEqualTo(FingerprintFailureReason.NOT_AN_IMAGE);
	}

	/** The other still formats reach the decoder and can be refused just the same. */
	@Test
	void everyStillSignatureTheDecoderRejectsIsARefusedStream(@TempDir Path tmp) throws IOException {
		Path png = Files.write(tmp.resolve("a.png"),
				new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2 });
		Path gif = Files.write(tmp.resolve("a.gif"), new byte[] { 'G', 'I', 'F', '8', '9', 'a', 1, 2 });
		Path bmp = Files.write(tmp.resolve("a.bmp"), new byte[] { 'B', 'M', 1, 2, 3, 4 });

		assertThat(FingerprintFailureClassifier.classify(png)).isEqualTo(FingerprintFailureReason.DECODER_REFUSED);
		assertThat(FingerprintFailureClassifier.classify(gif)).isEqualTo(FingerprintFailureReason.DECODER_REFUSED);
		assertThat(FingerprintFailureClassifier.classify(bmp)).isEqualTo(FingerprintFailureReason.DECODER_REFUSED);
	}

	/** Too short to hold any signature - and not blank, so not a loss either. */
	@Test
	void aFileTooShortToCarryASignatureIsNotAnImage(@TempDir Path tmp) throws IOException {
		Path stub = Files.write(tmp.resolve("stub.jpg"), new byte[] { 1 });

		assertThat(FingerprintFailureClassifier.classify(stub)).isEqualTo(FingerprintFailureReason.NOT_AN_IMAGE);
	}

	@Test
	void everyTerminalReasonStopsRetryingAndOnlyUnknownDoesNot() {
		assertThat(FingerprintFailureReason.CORRUPTED_FILE.terminal()).isTrue();
		assertThat(FingerprintFailureReason.NOT_AN_IMAGE.terminal()).isTrue();
		assertThat(FingerprintFailureReason.UNSUPPORTED_FORMAT.terminal()).isTrue();
		assertThat(FingerprintFailureReason.DECODER_REFUSED.terminal()).isTrue();
		assertThat(FingerprintFailureReason.UNKNOWN.terminal()).isFalse();
	}

	private static byte[] riff(String chunk) {
		byte[] bytes = new byte[32];

		System.arraycopy("RIFF".getBytes(), 0, bytes, 0, 4);
		System.arraycopy("WEBP".getBytes(), 0, bytes, 8, 4);
		System.arraycopy(chunk.getBytes(), 0, bytes, 12, 4);

		return bytes;
	}
}