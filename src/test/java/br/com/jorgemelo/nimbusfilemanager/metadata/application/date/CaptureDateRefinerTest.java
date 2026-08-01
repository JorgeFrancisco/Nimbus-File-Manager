package br.com.jorgemelo.nimbusfilemanager.metadata.application.date;

import java.time.LocalDateTime;
import java.time.Month;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.RefinedDate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;

class CaptureDateRefinerTest {

	private final CaptureDateRefiner refiner = new CaptureDateRefiner();

	private static final LocalDateTime NAME_DAY = LocalDateTime.of(2024, Month.JANUARY, 2, 0, 0); // midnight

	@Test
	void shouldAdoptModifiedOverCreatedWhenBothMatchTheDay() {
		LocalDateTime created = LocalDateTime.of(2024, Month.JANUARY, 2, 23, 0);
		LocalDateTime modified = LocalDateTime.of(2024, Month.JANUARY, 2, 14, 36, 14);

		RefinedDate result = refiner.refine(NAME_DAY, DateSource.FILE_NAME, created, modified);

		Assertions.assertThat(result.captureDate()).isEqualTo(modified);
		Assertions.assertThat(result.dateSource()).isEqualTo(DateSource.FILE_NAME_CONFIRMED);
	}

	@Test
	void shouldFallBackToCreatedWhenModifiedDoesNotMatchDay() {
		LocalDateTime created = LocalDateTime.of(2024, Month.JANUARY, 2, 9, 0);
		LocalDateTime modified = LocalDateTime.of(2024, Month.JANUARY, 3, 9, 0); // other day

		RefinedDate result = refiner.refine(NAME_DAY, DateSource.FILE_NAME, created, modified);

		Assertions.assertThat(result.captureDate()).isEqualTo(created);
		Assertions.assertThat(result.dateSource()).isEqualTo(DateSource.FILE_NAME_CONFIRMED);
	}

	@Test
	void shouldKeepNameDateWhenNoFilesystemTimestampMatchesDay() {
		LocalDateTime created = LocalDateTime.of(2024, Month.JULY, 7, 17, 58); // sync date, other day
		LocalDateTime modified = LocalDateTime.of(2024, Month.JULY, 7, 17, 58);

		RefinedDate result = refiner.refine(NAME_DAY, DateSource.FILE_NAME, created, modified);

		Assertions.assertThat(result.captureDate()).isEqualTo(NAME_DAY);
		Assertions.assertThat(result.dateSource()).isEqualTo(DateSource.FILE_NAME);
	}

	@Test
	void shouldNotRefineWhenNameAlreadyCarriesATime() {
		LocalDateTime nameWithTime = LocalDateTime.of(2024, Month.JANUARY, 2, 10, 30, 45);
		LocalDateTime sameDay = LocalDateTime.of(2024, Month.JANUARY, 2, 14, 0);

		RefinedDate result = refiner.refine(nameWithTime, DateSource.FILE_NAME, sameDay, sameDay);

		Assertions.assertThat(result.captureDate()).isEqualTo(nameWithTime);
		Assertions.assertThat(result.dateSource()).isEqualTo(DateSource.FILE_NAME);
	}

	@Test
	void shouldOnlyRefineFileNameSource() {
		LocalDateTime sameDay = LocalDateTime.of(2024, Month.JANUARY, 2, 14, 0);

		for (DateSource other : new DateSource[] { DateSource.EXIF, DateSource.MEDIA_INFO, DateSource.FOLDER_LAYOUT,
				DateSource.FILE_CREATED_AT }) {
			RefinedDate result = refiner.refine(NAME_DAY, other, sameDay, sameDay);

			Assertions.assertThat(result.captureDate()).isEqualTo(NAME_DAY);
			Assertions.assertThat(result.dateSource()).isEqualTo(other);
		}
	}

	@Test
	void shouldBeNullSafe() {
		Assertions.assertThat(refiner.refine(null, DateSource.FILE_NAME, null, null).captureDate()).isNull();

		RefinedDate onlyCreated = refiner.refine(NAME_DAY, DateSource.FILE_NAME,
				LocalDateTime.of(2024, Month.JANUARY, 2, 8, 0), null);

		Assertions.assertThat(onlyCreated.captureDate()).isEqualTo(LocalDateTime.of(2024, Month.JANUARY, 2, 8, 0));
		Assertions.assertThat(onlyCreated.dateSource()).isEqualTo(DateSource.FILE_NAME_CONFIRMED);
	}
}