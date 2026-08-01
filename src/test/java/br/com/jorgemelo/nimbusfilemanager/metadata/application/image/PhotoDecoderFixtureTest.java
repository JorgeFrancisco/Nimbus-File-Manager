package br.com.jorgemelo.nimbusfilemanager.metadata.application.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.DecodedPhoto;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.DecoderType;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.OrientationSource;

/**
 * Fixture-backed decoder tests that need real binary inputs: WEBP (lossy,
 * lossless, alpha, animated - the plugin only engages on genuine webp bytes)
 * and the eight EXIF orientations (metadata-extractor reads real EXIF, not a
 * synthetic BufferedImage).
 *
 * <p>
 * Fixtures live under {@code src/test/resources/photo/**} and are generated
 * with the project's bundled ffmpeg (see docs plan §14); they are
 * intentionally not committed, so each test is skipped
 * ({@link Assumptions#assumeTrue}) when its fixture is absent rather than
 * failing the suite on a clean checkout.
 */
class PhotoDecoderFixtureTest {

	private final PhotoDecoder decoder = new PhotoDecoder();

	@ParameterizedTest
	@ValueSource(strings = { "lossy", "lossless" })
	void decodesWebpThroughPlugin(String variant) throws Exception {
		Path file = fixture("photo/webp/" + variant + ".webp");
		Assumptions.assumeTrue(file != null, "missing webp fixture: " + variant);

		DecodedPhoto decoded = decoder.decode(file, null);

		assertThat(decoded.decoder()).isEqualTo(DecoderType.WEBP_PLUGIN);
		assertThat(decoded.image()).isNotNull();
		assertThat(decoded.image().getWidth()).isPositive();
		assertThat(decoded.image().getHeight()).isPositive();
		assertThat(decoded.image().getType()).isEqualTo(BufferedImage.TYPE_INT_RGB);
	}

	@Test
	void decodesAlphaWebpAndFlattensOverWhite() throws Exception {
		Path file = fixture("photo/webp/alpha.webp");
		Assumptions.assumeTrue(file != null, "missing alpha webp fixture");

		DecodedPhoto decoded = decoder.decode(file, null);

		assertThat(decoded.decoder()).isEqualTo(DecoderType.WEBP_PLUGIN);
		assertThat(decoded.alphaFlattened()).isTrue();
		assertThat(decoded.image().getType()).isEqualTo(BufferedImage.TYPE_INT_RGB);

		// A 50%-transparent red over white must lie between red and white, never pure
		// red (which is what a missing flatten would leave) - so blue channel > 0.
		Color pixel = new Color(decoded.image().getRGB(0, 0));
		assertThat(pixel.getBlue()).isGreaterThan(0);
	}

	@Test
	void decodesAnimatedWebpFrameZero() throws Exception {
		Path file = fixture("photo/webp/animated.webp");
		Assumptions.assumeTrue(file != null, "missing animated webp fixture");

		DecodedPhoto decoded = decoder.decode(file, null);

		assertThat(decoded.decoder()).isEqualTo(DecoderType.WEBP_PLUGIN);
		assertThat(decoded.frameIndex()).isZero();

		// Frame 0 is red; frame 1 is blue. A wrong frame would flip this.
		Color pixel = new Color(decoded.image().getRGB(1, 1));
		assertThat(pixel.getRed()).isGreaterThan(pixel.getBlue());
	}

	@ParameterizedTest
	@ValueSource(ints = { 1, 2, 3, 4 })
	void nonSwappingExifOrientationsKeepLandscapeDimensions(int orientation) throws Exception {
		Path file = fixture("photo/exif/orientation_" + orientation + ".jpg");
		Assumptions.assumeTrue(file != null, "missing exif fixture: " + orientation);

		DecodedPhoto decoded = decoder.decode(file, null);

		assertThat(decoded.orientationSource()).isEqualTo(OrientationSource.EXIF);
		assertThat(decoded.image().getWidth()).isEqualTo(200);
		assertThat(decoded.image().getHeight()).isEqualTo(120);
	}

	@ParameterizedTest
	@ValueSource(ints = { 5, 6, 7, 8 })
	void swappingExifOrientationsTransposeToPortrait(int orientation) throws Exception {
		Path file = fixture("photo/exif/orientation_" + orientation + ".jpg");
		Assumptions.assumeTrue(file != null, "missing exif fixture: " + orientation);

		DecodedPhoto decoded = decoder.decode(file, null);

		assertThat(decoded.orientationSource()).isEqualTo(OrientationSource.EXIF);
		assertThat(decoded.image().getWidth()).isEqualTo(120);
		assertThat(decoded.image().getHeight()).isEqualTo(200);
	}

	@Test
	void exifOrientationTakesPrecedenceOverDbRotation() throws Exception {
		// orientation_6 has EXIF=6 (swap). A conflicting DB rotation of 0/none must not
		// override it, and EXIF must win even when a DB rotation is supplied.
		Path file = fixture("photo/exif/orientation_6.jpg");
		Assumptions.assumeTrue(file != null, "missing exif fixture: 6");

		DecodedPhoto decoded = decoder.decode(file, 180);

		assertThat(decoded.orientationSource()).isEqualTo(OrientationSource.EXIF);
		// EXIF 6 swap wins (portrait), not the 180 DB rotation (which would stay
		// landscape).
		assertThat(decoded.image().getWidth()).isEqualTo(120);
		assertThat(decoded.image().getHeight()).isEqualTo(200);
	}

	private static Path fixture(String classpathRelative) throws Exception {
		URL url = PhotoDecoderFixtureTest.class.getClassLoader().getResource(classpathRelative);

		return url == null ? null : Paths.get(url.toURI());
	}
}