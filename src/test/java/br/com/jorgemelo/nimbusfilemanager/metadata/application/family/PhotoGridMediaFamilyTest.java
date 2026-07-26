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
 * Covers the whole PhotoGrid family from its single home: detection by name,
 * the subcategory facet and the date facet - including the epoch-millis token
 * that takes precedence over the shared {@code yyyyMMdd} patterns.
 */
class PhotoGridMediaFamilyTest {

	private final Clock clock = Clock.fixed(Instant.parse("2026-07-25T12:00:00Z"), ZoneOffset.UTC);
	private final PhotoGridMediaFamily family = new PhotoGridMediaFamily(clock);

	@Test
	void matchesNameShouldAcceptThePhotoGridPrefixInAnyCase() {
		Assertions.assertThat(PhotoGridMediaFamily.matchesName("PhotoGrid_1443567518248.jpg")).isTrue();
		Assertions.assertThat(PhotoGridMediaFamily.matchesName("photogrid-20240102.jpg")).isTrue();
		Assertions.assertThat(PhotoGridMediaFamily.matchesName("PHOTOGRID")).isTrue();
	}

	@Test
	void matchesNameShouldRejectNonPhotoGridNames() {
		Assertions.assertThat(PhotoGridMediaFamily.matchesName("IMG_20240102_103000.jpg")).isFalse();
		Assertions.assertThat(PhotoGridMediaFamily.matchesName("my-PhotoGrid-collage.jpg")).isFalse();
		Assertions.assertThat(PhotoGridMediaFamily.matchesName("")).isFalse();
		Assertions.assertThat(PhotoGridMediaFamily.matchesName(null)).isFalse();
	}

	@Test
	void subcategoryFacetShouldSupportTheNameAloneAndOwnThePhotoGridSubcategory() {
		Assertions.assertThat(family.supports("PhotoGrid_1443567518248.jpg", "C:/media/x.jpg")).isTrue();
		Assertions.assertThat(family.supports("holiday.jpg", "C:/media/PhotoGrid/holiday.jpg")).isFalse();

		Assertions.assertThat(family.subcategory()).isEqualTo(MediaSubcategory.PHOTOGRID);
		Assertions.assertThat(family.name()).isEqualTo("022_PHOTOGRID");
	}

	@Test
	void resolveShouldReadTheEpochMillisTokenInTheConfiguredZone() {
		Assertions.assertThat(family.resolve("PhotoGrid_1443567518248.jpg"))
				.isEqualTo(LocalDateTime.of(2015, Month.SEPTEMBER, 29, 22, 58, 38, 248_000_000));
	}

	/**
	 * The epoch token wins over the {@code yyyyMMdd} fallback even though the
	 * 13-digit run also contains a parseable 8-digit prefix.
	 */
	@Test
	void resolveShouldPreferTheEpochMillisTokenOverTheDatePatterns() {
		Assertions.assertThat(family.resolve("PhotoGrid-1443567518248-20240102.jpg"))
				.isEqualTo(LocalDateTime.of(2015, Month.SEPTEMBER, 29, 22, 58, 38, 248_000_000));
	}

	@Test
	void resolveShouldFallBackToTheDateAndTimePatternWhenThereIsNoEpochToken() {
		Assertions.assertThat(family.resolve("PhotoGrid_20240102_103000.jpg"))
				.isEqualTo(LocalDateTime.of(2024, Month.JANUARY, 2, 10, 30, 0));
	}

	@Test
	void resolveShouldFallBackToTheDateOnlyPatternWhenThereIsNoTime() {
		Assertions.assertThat(family.resolve("PhotoGrid_20240102.jpg"))
				.isEqualTo(LocalDateTime.of(2024, Month.JANUARY, 2, 0, 0));
	}

	/**
	 * A 13-digit token far outside the plausible capture range is a mis-parse, not
	 * a date: it is rejected, and the 8-digit fallback cannot rescue it either.
	 */
	@Test
	void resolveShouldReturnNullWhenTheEpochTokenIsOutsideThePlausibleRange() {
		Assertions.assertThat(family.resolve("PhotoGrid_9999999999999.jpg")).isNull();
		Assertions.assertThat(family.resolve("PhotoGrid_0000000000001.jpg")).isNull();
	}

	@Test
	void resolveShouldReturnNullWhenTheNameCarriesNoDateAtAll() {
		Assertions.assertThat(family.resolve("PhotoGrid_collage.jpg")).isNull();
	}
}