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
 * Covers the FaceApp family from its single home: detection by name, the
 * subcategory facet and the epoch-millis date left by an export that strips the
 * original EXIF.
 */
class FaceAppMediaFamilyTest {

	private final Clock clock = Clock.fixed(Instant.parse("2026-07-25T12:00:00Z"), ZoneOffset.UTC);
	private final FaceAppMediaFamily family = new FaceAppMediaFamily(clock);

	@Test
	void matchesNameShouldAcceptTheFaceAppPrefixInAnyCase() {
		Assertions.assertThat(FaceAppMediaFamily.matchesName("FaceApp_1563115590705.jpg")).isTrue();
		Assertions.assertThat(FaceAppMediaFamily.matchesName("faceapp-1563115590705.jpg")).isTrue();
	}

	@Test
	void matchesNameShouldRejectNamesThatOnlyContainThePrefix() {
		Assertions.assertThat(FaceAppMediaFamily.matchesName("edited-FaceApp_1563115590705.jpg")).isFalse();
		Assertions.assertThat(FaceAppMediaFamily.matchesName("AirBrush_20171006202756.jpg")).isFalse();
		Assertions.assertThat(FaceAppMediaFamily.matchesName("")).isFalse();
		Assertions.assertThat(FaceAppMediaFamily.matchesName(null)).isFalse();
	}

	@Test
	void subcategoryFacetShouldSupportTheNameAloneAndMapToOther() {
		Assertions.assertThat(family.supports("FaceApp_1563115590705.jpg", "C:/media/x.jpg")).isTrue();
		Assertions.assertThat(family.supports("holiday.jpg", "C:/media/FaceApp/holiday.jpg")).isFalse();

		Assertions.assertThat(family.subcategory()).isEqualTo(MediaSubcategory.OTHER);
		Assertions.assertThat(family.name()).isEqualTo("026_FACEAPP");
	}

	@Test
	void resolveShouldReadTheEpochMillisTokenInTheConfiguredZone() {
		Assertions.assertThat(family.resolve("FaceApp_1563115590705.jpg"))
				.isEqualTo(LocalDateTime.of(2019, Month.JULY, 14, 14, 46, 30, 705_000_000));
	}

	@Test
	void resolveShouldReturnNothingWhenTheTokenIsNotAPlausibleEpoch() {
		Assertions.assertThat(family.resolve("FaceApp_0000000000001.jpg")).isNull();
		Assertions.assertThat(family.resolve("FaceApp_9999999999999.jpg")).isNull();
		Assertions.assertThat(family.resolve("FaceApp_156311559.jpg")).isNull();
	}
}