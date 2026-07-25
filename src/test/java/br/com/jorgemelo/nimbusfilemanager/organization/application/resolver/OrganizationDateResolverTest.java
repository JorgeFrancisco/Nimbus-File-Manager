package br.com.jorgemelo.nimbusfilemanager.organization.application.resolver;

import java.time.LocalDateTime;
import java.time.Month;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection.OrganizationCandidate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

class OrganizationDateResolverTest {

	private final OrganizationDateResolver resolver = new OrganizationDateResolver();

	@Test
	void shouldPreferCaptureDate() {
		var date = resolver.resolve(candidate(LocalDateTime.of(2024, Month.MAY, 9, 10, 30), 2023, 1, 1, "202301"));

		Assertions.assertThat(date.yearMonth()).isEqualTo("202405");
		Assertions.assertThat(date.day()).isEqualTo("09");
		Assertions.assertThat(date.missingDate()).isFalse();
	}

	@Test
	void shouldUseExplicitDateWhenCaptureDateIsMissing() {
		var date = resolver.resolve(candidate(null, 2023, 7, 4, null));

		Assertions.assertThat(date.yearMonth()).isEqualTo("202307");
		Assertions.assertThat(date.day()).isEqualTo("04");
		Assertions.assertThat(date.missingDate()).isFalse();
	}

	@Test
	void shouldUseYearMonthWhenDayIsValid() {
		Assertions.assertThat(resolver.resolve(candidate(null, null, null, 8, "2024-03")).yearMonth())
				.isEqualTo("202403");
		Assertions.assertThat(resolver.resolve(candidate(null, null, null, 8, "202403")).yearMonth())
				.isEqualTo("202403");
	}

	@Test
	void shouldMarkMissingWhenDateCannotBeResolved() {
		var date = resolver.resolve(candidate(null, 1899, 13, 40, "invalid"));

		Assertions.assertThat(date.yearMonth()).isEqualTo(OrganizationDateResolver.SEM_DATA);
		Assertions.assertThat(date.day()).isEqualTo(OrganizationDateResolver.DIA_SEM_DATA);
		Assertions.assertThat(date.missingDate()).isTrue();
	}

	@ParameterizedTest
	@CsvSource({ "1900,1,1,190001,01", "2100,12,31,210012,31" })
	void shouldAcceptExplicitDateAtSupportedBoundaries(int year, int month, int day, String expectedYearMonth,
			String expectedDay) {
		var date = resolver.resolve(candidate(null, year, month, day, null));

		Assertions.assertThat(date.yearMonth()).isEqualTo(expectedYearMonth);
		Assertions.assertThat(date.day()).isEqualTo(expectedDay);
		Assertions.assertThat(date.missingDate()).isFalse();
	}

	@ParameterizedTest
	@CsvSource({ "1899,1,1", "2101,1,1", "2024,0,1", "2024,13,1", "2024,1,0", "2024,1,32" })
	void shouldRejectExplicitDateOutsideSupportedBoundaries(int year, int month, int day) {
		var date = resolver.resolve(candidate(null, year, month, day, null));

		Assertions.assertThat(date.yearMonth()).isEqualTo(OrganizationDateResolver.SEM_DATA);
		Assertions.assertThat(date.day()).isEqualTo(OrganizationDateResolver.DIA_SEM_DATA);
		Assertions.assertThat(date.missingDate()).isTrue();
	}

	@ParameterizedTest
	@CsvSource({ "190001,1,190001,01", "210012,31,210012,31", "1900-01,1,190001,01", "2100-12,31,210012,31" })
	void shouldAcceptYearMonthAtSupportedBoundaries(String yearMonth, int day, String expectedYearMonth,
			String expectedDay) {
		var date = resolver.resolve(candidate(null, null, null, day, yearMonth));

		Assertions.assertThat(date.yearMonth()).isEqualTo(expectedYearMonth);
		Assertions.assertThat(date.day()).isEqualTo(expectedDay);
		Assertions.assertThat(date.missingDate()).isFalse();
	}

	@ParameterizedTest
	@CsvSource({ "189912,1", "210101,1", "202400,1", "202413,1", "202401,0", "202401,32", "invalid,1", "' ',1" })
	void shouldRejectYearMonthOrDayOutsideSupportedBoundaries(String yearMonth, int day) {
		var date = resolver.resolve(candidate(null, null, null, day, yearMonth));

		Assertions.assertThat(date.yearMonth()).isEqualTo(OrganizationDateResolver.SEM_DATA);
		Assertions.assertThat(date.day()).isEqualTo(OrganizationDateResolver.DIA_SEM_DATA);
		Assertions.assertThat(date.missingDate()).isTrue();
	}

	private OrganizationCandidate candidate(LocalDateTime captureDate, Integer year, Integer month, Integer day,
			String yearMonth) {
		return new OrganizationCandidate(1L, "photo.jpg", "jpg", FileType.PHOTO, 100L, "C:/media/photo.jpg", "C:/media",
				year, month, day, yearMonth, captureDate, FileCategory.MEDIA, MediaSubcategory.CAMERA);
	}
}