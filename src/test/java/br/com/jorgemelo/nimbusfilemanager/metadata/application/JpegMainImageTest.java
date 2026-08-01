package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shape checked here is a Samsung panorama's: an ordinary baseline JPEG,
 * then a vendor trailer holding the source frames - whole JPEGs of their own,
 * which is what makes a decoder reading past the end lose its way.
 */
class JpegMainImageTest {

	private static final byte[] TRAILER = { 'S', 'E', 'F', 'T', 0x11, 0x22 };

	/** ffmpeg reads into the trailer and gives up; the image alone decodes. */
	@Test
	void dropsTheTrailerACameraAppendedAfterTheImage(@TempDir Path tmp) throws IOException {
		byte[] image = jpeg();

		Path panorama = Files.write(tmp.resolve("panorama.jpg"), concat(image, TRAILER));

		byte[] cut = JpegMainImage.withoutTrailer(panorama);

		assertThat(cut).isEqualTo(image);
	}

	/** An ordinary photo has nothing behind it and is decoded from its own file. */
	@Test
	void aPhotoWithNothingBehindItIsLeftAlone(@TempDir Path tmp) throws IOException {
		Path photo = Files.write(tmp.resolve("photo.jpg"), jpeg());

		assertThat(JpegMainImage.withoutTrailer(photo)).isEmpty();
	}

	/**
	 * Restart markers and stuffed FF bytes belong to the image: mistaking either
	 * for the end would cut a photo in half.
	 */
	@Test
	void restartMarkersAndStuffedBytesAreReadAsImageData(@TempDir Path tmp) throws IOException {
		byte[] scan = { 1, (byte) 0xFF, 0x00, 2, (byte) 0xFF, (byte) 0xD3, 3, (byte) 0xFF, 0x00, 4 };

		byte[] image = jpegWithScan(scan);

		Path panorama = Files.write(tmp.resolve("restarts.jpg"), concat(image, TRAILER));

		assertThat(JpegMainImage.withoutTrailer(panorama)).isEqualTo(image);
	}

	/** Fill bytes may precede a marker, and only the first of them is consumed. */
	@Test
	void fillBytesBeforeTheEndMarkerAreNotMistakenForImageData(@TempDir Path tmp) throws IOException {
		byte[] image = jpegWithScan(new byte[] { 7, (byte) 0xFF, (byte) 0xFF, (byte) 0xD9 });

		Path photo = Files.write(tmp.resolve("fill.jpg"), concat(image, TRAILER));

		byte[] cut = JpegMainImage.withoutTrailer(photo);

		assertThat(cut).endsWith(new byte[] { (byte) 0xFF, (byte) 0xD9 })
				.hasSizeLessThan(image.length + TRAILER.length);
	}

	/**
	 * A stream that runs into the trailer without its own end marker still has to
	 * come out closed, since a decoder is entitled to expect one.
	 */
	@Test
	void anImageThatNeverClosedIsClosedOnTheWayOut(@TempDir Path tmp) throws IOException {
		byte[] unclosed = concat(header(), new byte[] { 5, 6, 7 });

		Path panorama = Files.write(tmp.resolve("unclosed.jpg"),
				concat(unclosed, new byte[] { (byte) 0xFF, (byte) 0xC4 }, TRAILER));

		byte[] cut = JpegMainImage.withoutTrailer(panorama);

		assertThat(cut).startsWith(new byte[] { (byte) 0xFF, (byte) 0xD8 })
				.endsWith(new byte[] { (byte) 0xFF, (byte) 0xD9 });
	}

	@Test
	void anythingThatIsNotAJpegIsLeftAlone(@TempDir Path tmp) throws IOException {
		Path webp = Files.write(tmp.resolve("sticker.webp"), "RIFF0000WEBPVP8 ".getBytes());

		assertThat(JpegMainImage.withoutTrailer(webp)).isEmpty();
	}

	@Test
	void aJpegWithoutAScanIsLeftAlone(@TempDir Path tmp) throws IOException {
		Path headerOnly = Files.write(tmp.resolve("header.jpg"),
				concat(new byte[] { (byte) 0xFF, (byte) 0xD8 }, segment((byte) 0xDB, new byte[] { 1, 2 })));

		assertThat(JpegMainImage.withoutTrailer(headerOnly)).isEmpty();
	}

	@Test
	void aFileTooShortToHoldAMarkerIsLeftAlone(@TempDir Path tmp) throws IOException {
		Path stub = Files.write(tmp.resolve("stub.jpg"), new byte[] { (byte) 0xFF });

		assertThat(JpegMainImage.withoutTrailer(stub)).isEmpty();
	}

	@Test
	void aFileThatOpensWithAnotherMarkerIsLeftAlone(@TempDir Path tmp) throws IOException {
		Path other = Files.write(tmp.resolve("other.jpg"), new byte[] { (byte) 0xFF, (byte) 0xC4, 0, 4, 1, 2 });

		assertThat(JpegMainImage.withoutTrailer(other)).isEmpty();
	}

	/** A second start marker and restarts are skipped on the way to the scan. */
	@Test
	void markersWithoutASegmentAreSteppedOverWhileReadingTheHeader(@TempDir Path tmp) throws IOException {
		byte[] header = concat(
				new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD2 },
				segment((byte) 0xDA, new byte[] { 3, 4 }));

		byte[] image = concat(header, new byte[] { 1, 2, (byte) 0xFF, (byte) 0xD9 });

		Path photo = Files.write(tmp.resolve("skips.jpg"), concat(image, TRAILER));

		assertThat(JpegMainImage.withoutTrailer(photo)).isEqualTo(image);
	}

	/**
	 * A segment claiming more bytes than the file holds is refused instead of read
	 * past the end - the length comes from outside and cannot be trusted.
	 */
	@Test
	void aSegmentClaimingMoreThanTheFileHoldsIsRefused(@TempDir Path tmp) throws IOException {
		byte[] lying = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDB, (byte) 0x7F, (byte) 0xFF, 1, 2 };

		Path broken = Files.write(tmp.resolve("lying.jpg"), lying);

		assertThat(JpegMainImage.withoutTrailer(broken)).isEmpty();
	}

	@Test
	void aSegmentClaimingLessThanItsOwnHeaderIsRefused(@TempDir Path tmp) throws IOException {
		byte[] tiny = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDB, 0, 1, 1, 2 };

		Path broken = Files.write(tmp.resolve("tiny-segment.jpg"), tiny);

		assertThat(JpegMainImage.withoutTrailer(broken)).isEmpty();
	}

	/** Image data running to the end of the file has no trailer to drop. */
	@Test
	void aScanThatRunsToTheEndOfTheFileIsLeftAlone(@TempDir Path tmp) throws IOException {
		Path photo = Files.write(tmp.resolve("open.jpg"), concat(header(), new byte[] { 1, 2, 3, 4 }));

		assertThat(JpegMainImage.withoutTrailer(photo)).isEmpty();
	}

	private static byte[] jpeg() throws IOException {
		return jpegWithScan(new byte[] { 1, 2, 3, (byte) 0xFF, 0x00, 4 });
	}

	private static byte[] jpegWithScan(byte[] scan) throws IOException {
		return concat(header(), scan, new byte[] { (byte) 0xFF, (byte) 0xD9 });
	}

	private static byte[] header() throws IOException {
		return concat(new byte[] { (byte) 0xFF, (byte) 0xD8 }, segment((byte) 0xDB, new byte[] { 1, 2 }),
				segment((byte) 0xDA, new byte[] { 3, 4 }));
	}

	private static byte[] segment(byte marker, byte[] payload) throws IOException {
		int length = payload.length + 2;

		return concat(new byte[] { (byte) 0xFF, marker, (byte) (length >> 8), (byte) length }, payload);
	}

	private static byte[] concat(byte[]... parts) throws IOException {
		ByteArrayOutputStream all = new ByteArrayOutputStream();

		for (byte[] part : parts) {
			all.write(part);
		}

		return all.toByteArray();
	}
}