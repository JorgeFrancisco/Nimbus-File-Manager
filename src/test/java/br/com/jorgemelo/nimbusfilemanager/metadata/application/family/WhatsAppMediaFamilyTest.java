package br.com.jorgemelo.nimbusfilemanager.metadata.application.family;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

/**
 * Covers the whole WhatsApp family from its single home: detection (name +
 * folder), the subcategory facet and the date facet.
 */
class WhatsAppMediaFamilyTest {

	private final WhatsAppMediaFamily family = new WhatsAppMediaFamily(Clock.systemDefaultZone());

	@Test
	void matchesNameShouldCoverEveryPrefixAndSeparator() {
		Assertions.assertThat(WhatsAppMediaFamily.matchesName("IMG-20240102-WA0001.jpg")).isTrue();
		Assertions.assertThat(WhatsAppMediaFamily.matchesName("VID_20221231_WA0007.mp4")).isTrue();
		Assertions.assertThat(WhatsAppMediaFamily.matchesName("null-20240102-WA0001.jpg")).isTrue();
		Assertions.assertThat(WhatsAppMediaFamily.matchesName("PTT-20190414-WA0009.opus")).isTrue();
		Assertions.assertThat(WhatsAppMediaFamily.matchesName("AUD-20260705-WA0003.opus")).isTrue();
		Assertions.assertThat(WhatsAppMediaFamily.matchesName("DOC-20260707-WA0001.pdf")).isTrue();
		Assertions.assertThat(WhatsAppMediaFamily.matchesName("STK-20210101-WA0001.webp")).isTrue();
	}

	@Test
	void matchesNameShouldRejectNonWhatsAppNames() {
		Assertions.assertThat(WhatsAppMediaFamily.matchesName("IMG_20240102_103000.jpg")).isFalse();
		Assertions.assertThat(WhatsAppMediaFamily.matchesName("holiday-photo.jpg")).isFalse();
		Assertions.assertThat(WhatsAppMediaFamily.matchesName(null)).isFalse();
	}

	/**
	 * Pins the exact accept/reject contract that the (rewritten, linear) signature
	 * must preserve: prefix letters, a separator, anything, another separator, then
	 * {@code WA} followed by digits - the rest of the name is irrelevant.
	 */
	@Test
	void matchesNameEquivalenceAcrossEdgeCases() {
		Assertions.assertThat(WhatsAppMediaFamily.matchesName("img-20240102-wa0001.jpg")).isTrue();
		Assertions.assertThat(WhatsAppMediaFamily.matchesName("IMG-20240102-WA0001-EDITED-WA0002.jpg")).isTrue();
		Assertions.assertThat(WhatsAppMediaFamily.matchesName("IMG_20240102_WA0007")).isTrue();
		Assertions.assertThat(WhatsAppMediaFamily.matchesName("X-Y-WA1")).isTrue();

		// Needs TWO separators (one after the prefix, one right before WA).
		Assertions.assertThat(WhatsAppMediaFamily.matchesName("IMG-WA0001.jpg")).isFalse();
		// Must start with letters.
		Assertions.assertThat(WhatsAppMediaFamily.matchesName("-20240102-WA0001.jpg")).isFalse();
		Assertions.assertThat(WhatsAppMediaFamily.matchesName("123-20240102-WA0001.jpg")).isFalse();
		// WA must be followed by at least one digit.
		Assertions.assertThat(WhatsAppMediaFamily.matchesName("IMG-20240102-WAxyz.jpg")).isFalse();
		Assertions.assertThat(WhatsAppMediaFamily.matchesName("IMG-20240102-WA.jpg")).isFalse();
		Assertions.assertThat(WhatsAppMediaFamily.matchesName("")).isFalse();
	}

	@Test
	void matchesNameStaysLinearOnAdversarialInput() {
		// A long name full of separators but with no "WA<digits>" token: the linear
		// signature rejects it immediately, where the former double-".*" shape
		// backtracked.
		String adversarial = "IMG-" + "a-".repeat(50_000) + "end.jpg";

		Assertions.assertThat(WhatsAppMediaFamily.matchesName(adversarial)).isFalse();
	}

	@Test
	void matchesPathShouldDetectWhatsAppFolder() {
		Assertions.assertThat(WhatsAppMediaFamily.matchesPath("C:/media/WHATSAPP/photo.jpg")).isTrue();
		Assertions.assertThat(WhatsAppMediaFamily.matchesPath("C:/media/photos/photo.jpg")).isFalse();
		Assertions.assertThat(WhatsAppMediaFamily.matchesPath(null)).isFalse();
	}

	@Test
	void subcategoryFacetShouldSupportNameOrFolder() {
		Assertions.assertThat(family.supports("IMG-20240102-WA0001.jpg", "C:/x/IMG-20240102-WA0001.jpg")).isTrue();
		Assertions.assertThat(family.supports("photo.jpg", "C:/media/WHATSAPP/photo.jpg")).isTrue();
		Assertions.assertThat(family.supports("photo.jpg", "C:/media/photo.jpg")).isFalse();
		Assertions.assertThat(family.subcategory()).isEqualTo(MediaSubcategory.WHATSAPP);
		Assertions.assertThat(family.name()).isEqualTo("010_WHATSAPP");
	}

	@Test
	void dateFacetShouldResolveDateFromName() {
		Assertions.assertThat(family.supports("PTT-20190414-WA0009.opus")).isTrue();
		Assertions.assertThat(family.supports("photo-20240102.jpg")).isFalse();
		Assertions.assertThat(family.resolve("IMG-20240102-WA0001.jpg"))
				.isEqualTo(LocalDateTime.of(2024, Month.JANUARY, 2, 0, 0));
		Assertions.assertThat(family.resolve("PTT-20190414-WA0009.opus"))
				.isEqualTo(LocalDateTime.of(2019, Month.APRIL, 14, 0, 0));
		Assertions.assertThat(family.resolve("VID_20221231_WA0007.mp4"))
				.isEqualTo(LocalDateTime.of(2022, Month.DECEMBER, 31, 0, 0));
	}
}