package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MediaOrientation;

class MediaOrientationResolverTest {

	private final MediaOrientationResolver resolver = new MediaOrientationResolver();

	@Test
	void normalizeRotationShouldKeepValuesInsideZeroTo359() {
		Assertions.assertThat(resolver.normalizeRotation(null)).isNull();
		Assertions.assertThat(resolver.normalizeRotation(-90)).isEqualTo(270);
		Assertions.assertThat(resolver.normalizeRotation(450)).isEqualTo(90);
		Assertions.assertThat(resolver.normalizeRotation(720)).isZero();
	}

	@Test
	void exifOrientationToRotationShouldMapKnownCodes() {
		Assertions.assertThat(resolver.exifOrientationToRotation(null)).isNull();
		Assertions.assertThat(resolver.exifOrientationToRotation(1)).isZero();
		Assertions.assertThat(resolver.exifOrientationToRotation(3)).isEqualTo(180);
		Assertions.assertThat(resolver.exifOrientationToRotation(6)).isEqualTo(90);
		Assertions.assertThat(resolver.exifOrientationToRotation(8)).isEqualTo(270);
	}

	@Test
	void displayDimensionsAndOrientationShouldAccountForRotation() {
		Assertions.assertThat(resolver.displayWidth(4000, 3000, 90)).isEqualTo(3000);
		Assertions.assertThat(resolver.displayHeight(4000, 3000, 90)).isEqualTo(4000);
		Assertions.assertThat(resolver.orientationType(4000, 3000, 90)).isEqualTo(MediaOrientation.PORTRAIT);

		Assertions.assertThat(resolver.displayWidth(4000, 3000, 0)).isEqualTo(4000);
		Assertions.assertThat(resolver.displayHeight(4000, 3000, 0)).isEqualTo(3000);
		Assertions.assertThat(resolver.orientationType(4000, 3000, 0)).isEqualTo(MediaOrientation.LANDSCAPE);

		Assertions.assertThat(resolver.orientationType(1000, 1000, 270)).isEqualTo(MediaOrientation.SQUARE);
		Assertions.assertThat(resolver.orientationType(null, 1000, 0)).isEqualTo(MediaOrientation.UNKNOWN);
	}
}