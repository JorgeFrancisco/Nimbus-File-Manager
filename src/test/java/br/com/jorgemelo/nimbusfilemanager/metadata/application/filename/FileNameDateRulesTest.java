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
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.MonthFirstDateTimeMediaFamily;
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
		Assertions.assertThat(rule.resolve("Screenshot_20240102.png")).isEqualTo(LocalDateTime.of(2024, Month.JANUARY,
				2, 0, 0));
	}

	@Test
	void photoGridShouldResolveDateFromEpochMillis() {
		// The PhotoGrid_<epochMillis> token carries the creation time (1443567518248 ms
		// is late September 2015); a fixed UTC clock keeps the conversion
		// deterministic.
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
		Assertions.assertThat(airBrush.resolve("AirBrush_20240102.jpg")).isEqualTo(LocalDateTime.of(2024, Month.JANUARY,
				2, 0, 0));
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
		Assertions.assertThat(generic.resolve("IMG_20240102.jpg")).isEqualTo(LocalDateTime.of(2024, Month.JANUARY, 2, 0,
				0));
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
	/**
	 * A share id in front of the timestamp used to cost the whole date: the first
	 * digit block of the id is not a date, and the rule gave up there instead of
	 * looking at the next candidate.
	 */
	@Test
	void genericShouldFindTheTimestampBehindALongNumericId() {
		GenericMediaFamily generic = new GenericMediaFamily(Clock.systemDefaultZone());

		Assertions.assertThat(generic.resolve("lv_7556956620933156149_20260704132915.mp4"))
				.isEqualTo(LocalDateTime.of(2026, Month.JULY, 4, 13, 29, 15));
	}

	/**
	 * A 14-digit run with no separator is a full timestamp; reading only its first
	 * 8 digits threw away the time of day.
	 */
	@Test
	void familiesShouldReadATimestampWithoutSeparators() {
		Assertions.assertThat(new GenericMediaFamily(Clock.systemDefaultZone()).resolve("20150524054234.jpg"))
				.isEqualTo(LocalDateTime.of(2015, Month.MAY, 24, 5, 42, 34));
		Assertions.assertThat(new AirBrushMediaFamily(Clock.systemDefaultZone()).resolve("AirBrush_20150524054234.jpg"))
				.isEqualTo(LocalDateTime.of(2015, Month.MAY, 24, 5, 42, 34));
	}

	/**
	 * The guard around the 14-digit rule matters: an id of 19 digits contains
	 * plenty of 14-digit windows, and none of them is a timestamp.
	 */
	@Test
	void aLongIdAloneYieldsNoDate() {
		Assertions.assertThat(new GenericMediaFamily(Clock.systemDefaultZone())
				.resolve("lv_7556956620933156149.mp4")).isNull();
	}

	/**
	 * Early camera phones named the file after MMddyyHHmmss with no separator,
	 * which the 8-digit families read as an implausible year and dropped; only the
	 * month-first reading keeps the real capture time of those.
	 */
	@Test
	void monthFirstFamilyShouldReadAPhoneTimestamp() {
		MonthFirstDateTimeMediaFamily monthFirst = new MonthFirstDateTimeMediaFamily(Clock.systemDefaultZone());

		Assertions.assertThat(monthFirst.supports("012708165237_H265.mp4")).isTrue();
		Assertions.assertThat(monthFirst.supports("20221110_110848.mp4")).isFalse();
		Assertions.assertThat(monthFirst.supports(null)).isFalse();
		// Runs before the generic scan, which reads the first 8 digits as a year.
		Assertions.assertThat(monthFirst.name()).isLessThan(new GenericMediaFamily(Clock.systemDefaultZone()).name());

		Assertions.assertThat(monthFirst.resolve("012708165237.jpg"))
				.isEqualTo(LocalDateTime.of(2008, Month.JANUARY, 27, 16, 52, 37));
		Assertions.assertThat(monthFirst.resolve("012708165237_H265.mp4"))
				.isEqualTo(LocalDateTime.of(2008, Month.JANUARY, 27, 16, 52, 37));
		Assertions.assertThat(monthFirst.resolve("020208222901_H265.mp4"))
				.isEqualTo(LocalDateTime.of(2008, Month.FEBRUARY, 2, 22, 29, 1));
		Assertions.assertThat(monthFirst.resolve("121507213458.jpg"))
				.isEqualTo(LocalDateTime.of(2007, Month.DECEMBER, 15, 21, 34, 58));
	}

	/**
	 * The same twelve digits also spell ddMMyyyy plus a counter, and nothing in
	 * the name tells the two apart - so an ambiguous run yields no date rather
	 * than a guess, and a run that is neither yields none either.
	 */
	@Test
	void monthFirstFamilyShouldRejectTheAmbiguousAndTheInvalid() {
		MonthFirstDateTimeMediaFamily monthFirst = new MonthFirstDateTimeMediaFamily(Clock.systemDefaultZone());

		Assertions.assertThat(monthFirst.resolve("031120081613 - 01.bmp")).isNull(); // 2008 counter 1613
		Assertions.assertThat(monthFirst.resolve("100620121090.jpg")).isNull(); // 2012 counter 1090
		Assertions.assertThat(monthFirst.resolve("012708256237.jpg")).isNull(); // hour 25
		Assertions.assertThat(monthFirst.resolve("808501710986.jpg")).isNull(); // month 80
		Assertions.assertThat(monthFirst.resolve("123456789012345.jpg")).isNull(); // 15-digit id
	}
}