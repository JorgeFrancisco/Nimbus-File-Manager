package br.com.jorgemelo.nimbusfilemanager.metadata.application.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.DecodedPhoto;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.DecoderType;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.OrientationSource;

/**
 * Fixture-free unit tests for {@link PhotoDecoder}. Every input image is
 * generated at runtime with {@code ImageIO.write} so nothing here depends on
 * ffmpeg or a committed binary. WEBP-specific behaviour (needs the plugin +
 * real webp bytes) is covered separately by the fixture-backed tests.
 */
class PhotoDecoderTest {

	private final PhotoDecoder decoder = new PhotoDecoder();

	@ParameterizedTest
	@ValueSource(strings = { "png", "bmp", "gif" })
	void decodesJdkNativeFormats(String format, @TempDir Path dir) throws Exception {
		Path file = dir.resolve("image." + format);
		writeSolid(file, format, 40, 30, Color.RED);

		DecodedPhoto decoded = decoder.decode(file, null);

		assertThat(decoded.image()).isNotNull();
		assertThat(decoded.decoder()).isEqualTo(DecoderType.IMAGEIO_NATIVE);
		assertThat(decoded.frameIndex()).isZero();
		assertThat(decoded.image().getType()).isEqualTo(BufferedImage.TYPE_INT_RGB);
	}

	@Test
	void flattensAlphaOverWhite(@TempDir Path dir) throws Exception {
		BufferedImage argb = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
		// Fully transparent pixels everywhere: nothing painted -> alpha 0.
		Path file = dir.resolve("alpha.png");
		ImageIO.write(argb, "png", file.toFile());

		DecodedPhoto decoded = decoder.decode(file, null);

		assertThat(decoded.alphaFlattened()).isTrue();
		assertThat(decoded.image().getType()).isEqualTo(BufferedImage.TYPE_INT_RGB);
		// Transparent source composited over white must read pure white.
		assertThat(new Color(decoded.image().getRGB(0, 0))).isEqualTo(Color.WHITE);
	}

	@Test
	void opaqueImageReportsNoAlphaFlatten(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("opaque.jpg");
		writeSolid(file, "jpg", 20, 20, Color.BLUE);

		DecodedPhoto decoded = decoder.decode(file, null);

		assertThat(decoded.alphaFlattened()).isFalse();
	}

	@Test
	void unsupportedFormatThrowsUnsupportedDecodeException(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("notanimage.txt");
		Files.writeString(file, "this is definitely not an image");

		assertThatExceptionOfType(UnsupportedDecodeException.class).isThrownBy(() -> decoder.decode(file, null));
	}

	@Test
	void corruptImageOfSupportedFormatThrowsIOException(@TempDir Path dir) throws Exception {
		Path good = dir.resolve("good.png");
		writeSolid(good, "png", 30, 30, Color.GREEN);

		// Keep the PNG signature so a reader is found, then truncate the pixel data so
		// the decode itself fails: that is "corrupt" (IOException), not "unsupported".
		byte[] bytes = Files.readAllBytes(good);
		Path corrupt = dir.resolve("corrupt.png");
		Files.write(corrupt, Arrays.copyOf(bytes, 40));

		assertThatExceptionOfType(IOException.class).isThrownBy(() -> decoder.decode(corrupt, null))
				.satisfies(e -> assertThat(e).isNotInstanceOf(UnsupportedDecodeException.class));
	}

	@Test
	void largeImageDecodesWithSubsamplingWithoutFullMaterialization(@TempDir Path dir) throws Exception {
		// A 4000x3000 image; HASH subsampling factor = min(4000,3000)/64 = 46, so the
		// intermediate is far smaller than the source but still >= the 9x8 grid.
		Path file = dir.resolve("large.png");
		writeSolid(file, "png", 4000, 3000, Color.GRAY);

		DecodedPhoto decoded = decoder.decode(file, null);

		assertThat(decoded.image().getWidth()).isLessThan(4000);
		assertThat(decoded.image().getHeight()).isLessThan(3000);
		assertThat(decoded.image().getWidth()).isGreaterThanOrEqualTo(9);
		assertThat(decoded.image().getHeight()).isGreaterThanOrEqualTo(8);
	}

	@Test
	void readsFrameZeroOfAnimatedGif(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("animated.gif");
		writeAnimatedGif(file);

		DecodedPhoto decoded = decoder.decode(file, null);

		assertThat(decoded.frameIndex()).isZero();

		// Frame 0 is red; a wrong frame (frame 1 is blue) would fail this.
		Color pixel = new Color(decoded.image().getRGB(2, 2));
		assertThat(pixel.getRed()).isGreaterThan(pixel.getBlue());
	}

	@Test
	void decodeIsDeterministicAcrossRuns(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("stable.png");
		writeGradient(file, 200, 160);

		DecodedPhoto a = decoder.decode(file, null);
		DecodedPhoto b = decoder.decode(file, null);

		assertThat(a.image().getWidth()).isEqualTo(b.image().getWidth());
		assertThat(a.image().getHeight()).isEqualTo(b.image().getHeight());

		for (int y = 0; y < a.image().getHeight(); y++) {
			for (int x = 0; x < a.image().getWidth(); x++) {
				assertThat(a.image().getRGB(x, y)).isEqualTo(b.image().getRGB(x, y));
			}
		}
	}

	@Test
	void missingFileThrowsIOException(@TempDir Path dir) {
		Path file = dir.resolve("does-not-exist.png");

		assertThatExceptionOfType(IOException.class).isThrownBy(() -> decoder.decode(file, null));
	}

	// --- orientation matrices (no fixture: synthetic in-memory image)
	// ------------------

	@ParameterizedTest
	@ValueSource(ints = { 1, 2, 3, 4 })
	void nonSwappingOrientationsKeepDimensions(int orientation) {
		BufferedImage source = markerImage(6, 4);

		BufferedImage result = decoder.applyExifOrientation(source, orientation);

		assertThat(result.getWidth()).isEqualTo(6);
		assertThat(result.getHeight()).isEqualTo(4);
	}

	@ParameterizedTest
	@ValueSource(ints = { 5, 6, 7, 8 })
	void swappingOrientationsTransposeDimensions(int orientation) {
		BufferedImage source = markerImage(6, 4);

		BufferedImage result = decoder.applyExifOrientation(source, orientation);

		assertThat(result.getWidth()).isEqualTo(4);
		assertThat(result.getHeight()).isEqualTo(6);
	}

	@Test
	void orientation6RotatesTopLeftMarkerToTopRight() {
		BufferedImage source = markerImage(4, 2);
		// Marker is a white pixel at top-left (0,0); the rest is black.

		BufferedImage rotated = decoder.applyExifOrientation(source, 6);

		// 90 CW: the original top-left corner moves to the top-right corner.
		assertThat(new Color(rotated.getRGB(rotated.getWidth() - 1, 0))).isEqualTo(Color.WHITE);
	}

	/**
	 * EXIF is user data and holds anything: the unspecified orientation 0 must
	 * leave the image as it is, never rotate it by guess or fail.
	 */
	@Test
	void anUnknownOrientationLeavesTheImageUnrotated() {
		BufferedImage source = markerImage(3, 2);

		BufferedImage result = decoder.applyExifOrientation(source, 0);

		assertThat(result.getWidth()).isEqualTo(3);
		assertThat(result.getHeight()).isEqualTo(2);
		assertThat(new Color(result.getRGB(0, 0))).isEqualTo(new Color(source.getRGB(0, 0)));
	}

	/** A transparent photo keeps its alpha channel through the rotation. */
	@Test
	void rotatingATransparentImageKeepsItsAlphaChannel() {
		BufferedImage source = new BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB);

		source.setRGB(0, 0, new Color(255, 0, 0, 128).getRGB());

		BufferedImage result = decoder.applyExifOrientation(source, 6);

		assertThat(result.getColorModel().hasAlpha()).isTrue();
	}

	@Test
	void orientation1IsIdentity() {
		BufferedImage source = markerImage(3, 3);

		assertThat(decoder.applyExifOrientation(source, 1)).isSameAs(source);
	}

	@Test
	void dbRotationMapsToEquivalentExifRotation() {
		BufferedImage source = markerImage(6, 4);

		assertThat(decoder.applyDbRotation(source, 90).getWidth()).isEqualTo(4);
		assertThat(decoder.applyDbRotation(source, 180).getWidth()).isEqualTo(6);
		assertThat(decoder.applyDbRotation(source, 270).getWidth()).isEqualTo(4);
		assertThat(decoder.applyDbRotation(source, 45)).isSameAs(source);
	}

	@Test
	void dbRotationAppliedOnlyWhenNoExifOrientation(@TempDir Path dir) throws Exception {
		// A plain PNG has no EXIF orientation, so a 90-degree DB rotation must apply.
		Path file = dir.resolve("dbrot.png");
		writeSolid(file, "png", 20, 10, Color.RED);

		DecodedPhoto decoded = decoder.decode(file, 90);

		assertThat(decoded.orientationSource()).isEqualTo(OrientationSource.DB_ROTATION);
		assertThat(decoded.image().getWidth()).isEqualTo(10);
		assertThat(decoded.image().getHeight()).isEqualTo(20);
	}

	@Test
	void noOrientationSourceWhenNeitherExifNorDbRotation(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("plain.png");
		writeSolid(file, "png", 20, 10, Color.RED);

		DecodedPhoto decoded = decoder.decode(file, 0);

		assertThat(decoded.orientationSource()).isEqualTo(OrientationSource.NONE);
	}

	@Test
	void thumbnailSubsamplingFactorsAreDeterministicIntegers() {
		assertThat(decoder.thumbnailSubsampling(6400, 4800)).isEqualTo(5);
		assertThat(decoder.thumbnailSubsampling(100, 100)).isEqualTo(1);
	}

	@Test
	void isWebpDetectsFormatNameCaseInsensitively() {
		assertThat(decoder.isWebp("webp")).isTrue();
		assertThat(decoder.isWebp("WebP")).isTrue();
		assertThat(decoder.isWebp("JPEG")).isFalse();
		assertThat(decoder.isWebp(null)).isFalse();
	}

	@Test
	void readExifOrientationReturnsZeroForFileWithoutExif(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("noexif.png");
		writeSolid(file, "png", 8, 8, Color.RED);

		assertThat(decoder.readExifOrientation(file)).isZero();
	}

	@Test
	void decodeIsRepeatableWithoutSharedState(@TempDir Path dir) {
		Path file = dir.resolve("both.png");

		assertThatNoException().isThrownBy(() -> {
			writeSolid(file, "png", 50, 50, Color.RED);
			decoder.decode(file, null);
			decoder.decode(file, null);
		});
	}

	// --- helpers
	// -----------------------------------------------------------------------

	private static void writeSolid(Path file, String format, int width, int height, Color color) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(color);
		graphics.fillRect(0, 0, width, height);
		graphics.dispose();

		if (!ImageIO.write(image, format, file.toFile())) {
			throw new IOException("No writer for " + format);
		}
	}

	private static void writeGradient(Path file, int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

		for (int x = 0; x < width; x++) {
			int value = (int) Math.round(255.0 * x / (width - 1));
			for (int y = 0; y < height; y++) {
				image.setRGB(x, y, new Color(value, value, value).getRGB());
			}
		}

		ImageIO.write(image, "png", file.toFile());
	}

	private static BufferedImage markerImage(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(Color.BLACK);
		graphics.fillRect(0, 0, width, height);
		graphics.dispose();
		image.setRGB(0, 0, Color.WHITE.getRGB());

		return image;
	}

	private static void writeAnimatedGif(Path file) throws IOException {
		BufferedImage frame0 = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
		Graphics2D g0 = frame0.createGraphics();
		g0.setColor(Color.RED);
		g0.fillRect(0, 0, 8, 8);
		g0.dispose();

		BufferedImage frame1 = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
		Graphics2D g1 = frame1.createGraphics();
		g1.setColor(Color.BLUE);
		g1.fillRect(0, 0, 8, 8);
		g1.dispose();

		var writer = ImageIO.getImageWritersByFormatName("gif").next();

		try (ImageOutputStream out = ImageIO.createImageOutputStream(file.toFile())) {
			writer.setOutput(out);
			writer.prepareWriteSequence(null);
			writer.writeToSequence(new IIOImage(frame0, null, null), writer.getDefaultWriteParam());
			writer.writeToSequence(new IIOImage(frame1, null, null), writer.getDefaultWriteParam());
			writer.endWriteSequence();
		} finally {
			writer.dispose();
		}
	}
}