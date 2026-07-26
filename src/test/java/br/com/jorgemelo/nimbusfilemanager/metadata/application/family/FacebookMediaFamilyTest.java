package br.com.jorgemelo.nimbusfilemanager.metadata.application.family;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneOffset;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

/**
 * Covers the Facebook family from its single home: detection by name, the
 * subcategory facet and the epoch-millis date that is the only one these
 * downloads carry.
 */
class FacebookMediaFamilyTest {

	private final Clock clock = Clock.fixed(Instant.parse("2026-07-25T12:00:00Z"), ZoneOffset.UTC);
	private final FacebookMediaFamily family = new FacebookMediaFamily(clock);

	@Test
	void matchesNameShouldAcceptTheFacebookPrefixInAnyCase() {
		Assertions.assertThat(FacebookMediaFamily.matchesName("FB_IMG_1425709614699.jpg")).isTrue();
		Assertions.assertThat(FacebookMediaFamily.matchesName("fb_img_1425709614699.jpg")).isTrue();
	}

	@Test
	void matchesNameShouldRejectNamesThatOnlyContainThePrefix() {
		Assertions.assertThat(FacebookMediaFamily.matchesName("shared-FB_IMG_1425709614699.jpg")).isFalse();
		Assertions.assertThat(FacebookMediaFamily.matchesName("IMG_20240102_103000.jpg")).isFalse();
		Assertions.assertThat(FacebookMediaFamily.matchesName("")).isFalse();
		Assertions.assertThat(FacebookMediaFamily.matchesName(null)).isFalse();
	}

	@Test
	void subcategoryFacetShouldSupportTheNameAloneAndMapToOther() {
		Assertions.assertThat(family.supports("FB_IMG_1425709614699.jpg", "C:/media/x.jpg")).isTrue();
		Assertions.assertThat(family.supports("holiday.jpg", "C:/media/Facebook/holiday.jpg")).isFalse();

		Assertions.assertThat(family.subcategory()).isEqualTo(MediaSubcategory.OTHER);
		Assertions.assertThat(family.name()).isEqualTo("024_FACEBOOK");
	}

	@Test
	void resolveShouldReadTheEpochMillisTokenInTheConfiguredZone() {
		Assertions.assertThat(family.resolve("FB_IMG_1425709614699.jpg"))
				.isEqualTo(LocalDateTime.of(2015, Month.MARCH, 7, 6, 26, 54, 699_000_000));
	}

	/**
	 * A token that is not an epoch in the plausible capture range leaves the date
	 * unresolved, so the extractor falls back instead of recording a bogus year.
	 */
	@Test
	void resolveShouldReturnNothingWhenTheTokenIsNotAPlausibleEpoch() {
		Assertions.assertThat(family.resolve("FB_IMG_0000000000001.jpg")).isNull();
		Assertions.assertThat(family.resolve("FB_IMG_9999999999999.jpg")).isNull();
		Assertions.assertThat(family.resolve("FB_IMG_142570961.jpg")).isNull();
		Assertions.assertThat(family.resolve("FB_IMG_holiday.jpg")).isNull();
	}
}