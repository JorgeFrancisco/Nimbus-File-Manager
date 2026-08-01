package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * WhatsApp ships ZIP/Lottie packages named {@code .webp}. They stay cataloged
 * for the Files screen but must be kept out of every media pipeline, so the
 * check has to be true only when a media extension meets archive bytes.
 */
class MediaProcessingPolicyTest {

	@Test
	void shouldFlagAMediaExtensionCarryingArchiveBytes() {
		Assertions.assertThat(MediaProcessingPolicy.isArchiveMasqueradingAsMedia("webp", "application/zip")).isTrue();
		Assertions
				.assertThat(MediaProcessingPolicy.isArchiveMasqueradingAsMedia("webp", "application/x-zip-compressed"))
				.isTrue();
	}

	/**
	 * The detected type arrives straight from the sniffer, so casing and padding
	 * must not change the verdict.
	 */
	@Test
	void shouldNormalizeTheDetectedMimeTypeBeforeDeciding() {
		Assertions.assertThat(MediaProcessingPolicy.isArchiveMasqueradingAsMedia("webp", "  APPLICATION/ZIP  "))
				.isTrue();
	}

	@Test
	void shouldNotFlagAMediaFileWhoseBytesAreReallyMedia() {
		Assertions.assertThat(MediaProcessingPolicy.isArchiveMasqueradingAsMedia("webp", "image/webp")).isFalse();
		Assertions.assertThat(MediaProcessingPolicy.isArchiveMasqueradingAsMedia("jpg", null)).isFalse();
	}

	/**
	 * A genuine archive is not "masquerading" - it never claimed to be media, so
	 * nothing is being kept out of a pipeline it would not enter anyway.
	 */
	@Test
	void shouldNotFlagAnArchiveThatIsHonestAboutItsExtension() {
		Assertions.assertThat(MediaProcessingPolicy.isArchiveMasqueradingAsMedia("zip", "application/zip")).isFalse();
	}

	@Test
	void thePathOverloadShouldTolerateANullFile() {
		Assertions.assertThat(MediaProcessingPolicy.isArchiveMasqueradingAsMedia((Path) null, "application/zip"))
				.isFalse();
		Assertions.assertThat(
				MediaProcessingPolicy.isArchiveMasqueradingAsMedia(Path.of("C:/media/sticker.webp"), "application/zip"))
				.isTrue();
	}
}