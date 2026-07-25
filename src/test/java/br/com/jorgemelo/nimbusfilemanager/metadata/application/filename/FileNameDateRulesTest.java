package br.com.jorgemelo.nimbusfilemanager.metadata.application.filename;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneOffset;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.AirBrushMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.DashedDateTimeMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.DottedDateMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.GenericMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.ImageUuidMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.PeachyMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.PhotoGridMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.ScreenshotMediaFamily;

/**
 * Date facet (name detection + capture-date extraction) of the migrated media
 * families. WhatsApp is covered by WhatsAppMediaFamilyTest.
 */
class FileNameDateRulesTest {

	@Test
	void screenshotShouldPreferDateTimeWhenPresent() {
		ScreenshotMediaFamily rule = new ScreenshotMediaFamily(Clock.systemDefaultZone());

		Assertions.assertThat(rule.supports("Screenshot_20240102_103045.png")).isTrue();
		// CAPTURA is now covered by the same single detection used for classification.
		Assertions.assertThat(rule.supports("Captura_20240102_103045.png")).isTrue();
		Assertions.assertThat(rule.resolve("Screenshot_20240102_103045.png"))
				.isEqualTo(LocalDateTime.of(2024, Month.JANUARY, 2, 10, 30, 45));
		Assertions.assertThat(rule.resolve("Screenshot_20240102.png")).isEqualTo(LocalDateTime.of(2024, Month.JANUARY, 2, 0, 0));
	}

	@Test
	void photoGridShouldResolveDateFromEpochMillis() {
		// The PhotoGrid_<epochMillis> token carries the creation time (1443567518248 ms
		// is late September 2015); a fixed UTC clock keeps the conversion deterministic.
		PhotoGridMediaFamily rule = new PhotoGridMediaFamily(Clock.system(ZoneOffset.UTC));

		Assertions.assertThat(rule.supports("PhotoGrid_1443567518248.jpg")).isTrue();
		Assertions.assertThat(rule.resolve("PhotoGrid_1443567518248.jpg"))
				.isEqualTo(LocalDateTime.ofInstant(Instant.ofEpochMilli(1443567518248L), ZoneOffset.UTC));
	}

	@Test
	void appSpecificFamiliesShouldResolveDateTime() {
		AirBrushMediaFamily airBrush = new AirBrushMediaFamily(Clock.systemDefaultZone());

		PeachyMediaFamily peachy = new PeachyMediaFamily(Clock.systemDefaultZone());

		ImageUuidMediaFamily imageUuid = new ImageUuidMediaFamily(Clock.systemDefaultZone());

		Assertions.assertThat(airBrush.supports("AirBrush_20240102_103045.jpg")).isTrue();
		Assertions.assertThat(airBrush.supports(null)).isFalse();
		Assertions.assertThat(peachy.supports("Peachy_20240102_103045.jpg")).isTrue();
		Assertions.assertThat(imageUuid.supports("IMAGE_20240102_103045.jpg")).isTrue();
		Assertions.assertThat(airBrush.resolve("AirBrush_20240102_103045.jpg"))
				.isEqualTo(LocalDateTime.of(2024, Month.JANUARY, 2, 10, 30, 45));
		Assertions.assertThat(airBrush.resolve("AirBrush_20240102.jpg")).isEqualTo(LocalDateTime.of(2024, Month.JANUARY, 2, 0, 0));
		Assertions.assertThat(peachy.resolve("Peachy_20240102_103045.jpg"))
				.isEqualTo(LocalDateTime.of(2024, Month.JANUARY, 2, 10, 30, 45));
		Assertions.assertThat(imageUuid.resolve("IMAGE_20240102_103045.jpg"))
				.isEqualTo(LocalDateTime.of(2024, Month.JANUARY, 2, 10, 30, 45));
	}

	@Test
	void dashedAndGenericFamiliesShouldResolveSupportedFormats() {
		DashedDateTimeMediaFamily dashed = new DashedDateTimeMediaFamily(Clock.systemDefaultZone());

		GenericMediaFamily generic = new GenericMediaFamily(Clock.systemDefaultZone());

		Assertions.assertThat(dashed.supports("2024-01-02 10.30.45.jpg")).isTrue();
		Assertions.assertThat(dashed.supports(null)).isFalse();
		Assertions.assertThat(generic.supports("VID_20240102_103045.mp4")).isTrue();
		Assertions.assertThat(generic.supports("video.mp4")).isFalse();
		Assertions.assertThat(dashed.resolve("2024-01-02 10.30.45.jpg"))
				.isEqualTo(LocalDateTime.of(2024, Month.JANUARY, 2, 10, 30, 45));
		Assertions.assertThat(dashed.resolve("2024-01-02_10-30-45.jpg"))
				.isEqualTo(LocalDateTime.of(2024, Month.JANUARY, 2, 10, 30, 45));
		Assertions.assertThat(generic.resolve("VID_20240102_103045.mp4"))
				.isEqualTo(LocalDateTime.of(2024, Month.JANUARY, 2, 10, 30, 45));
		Assertions.assertThat(generic.resolve("IMG_20240102.jpg")).isEqualTo(LocalDateTime.of(2024, Month.JANUARY, 2, 0, 0));
	}

	@Test
	void dottedDateFamilyShouldTakeFirstPlausibleYearFirstDate() {
		DottedDateMediaFamily dotted = new DottedDateMediaFamily(Clock.systemDefaultZone());

		Assertions.assertThat(dotted.supports("01. 2024.03.03. Limpeza.pdf")).isTrue();
		Assertions.assertThat(dotted.supports("no date here.pdf")).isFalse();
		Assertions.assertThat(dotted.supports("report v1.2.3.pdf")).isFalse(); // version, not a year

		Assertions.assertThat(dotted.resolve("01. 2024.03.03. Limpeza.pdf"))
				.isEqualTo(LocalDateTime.of(2024, Month.MARCH, 3, 0, 0));
		// two dates: year-first wins, trailing DD.MM.YYYY is ignored
		Assertions.assertThat(dotted.resolve("2024.07.18. Edital 30.07.2024.pdf"))
				.isEqualTo(LocalDateTime.of(2024, Month.JULY, 18, 0, 0));
		// implausible month/day is not matched
		Assertions.assertThat(dotted.supports("2024.13.40 report.pdf")).isFalse();
		Assertions.assertThat(dotted.resolve("2024.13.40 report.pdf")).isNull();
	}

	@Test
	void familiesShouldRejectInvalidOrUnreasonableDates() {
		Assertions.assertThat(new GenericMediaFamily(Clock.systemDefaultZone()).resolve("IMG_18991231.jpg")).isNull();
		Assertions.assertThat(new GenericMediaFamily(Clock.systemDefaultZone()).resolve("IMG_20241340.jpg")).isNull();
		Assertions.assertThat(new DashedDateTimeMediaFamily(Clock.systemDefaultZone()).resolve("2024-01-02.jpg"))
				.isNull();
	}
}