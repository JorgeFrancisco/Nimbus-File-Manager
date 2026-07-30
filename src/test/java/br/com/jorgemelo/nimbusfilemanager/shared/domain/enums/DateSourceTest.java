package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.Month;

import org.junit.jupiter.api.Test;

/**
 * The trust ladder decides which copy of a duplicate keeps its date and whether
 * a converted file inherits the date of the file it replaces, so what it ranks
 * above what is a rule of its own, not an implementation detail.
 */
class DateSourceTest {

	private static final LocalDateTime MEIA_NOITE = LocalDateTime.of(2017, Month.OCTOBER, 6, 0, 0);
	private static final LocalDateTime COM_HORA = LocalDateTime.of(2017, Month.OCTOBER, 6, 20, 27, 56);

	@Test
	void embeddedDatesOutrankEverythingAndFilesystemOnesRankLast() {
		assertThat(DateSource.EXIF.trust()).isEqualTo(DateSource.MEDIA_INFO.trust())
				.isGreaterThan(DateSource.FILE_NAME_CONFIRMED.trust());
		assertThat(DateSource.FILE_NAME_CONFIRMED.trust()).isGreaterThan(DateSource.FILE_NAME.trust());
		assertThat(DateSource.FILE_NAME.trust()).isGreaterThan(DateSource.FILE_MODIFIED_AT.trust());
		assertThat(DateSource.FILE_MODIFIED_AT.trust()).isGreaterThan(DateSource.FILE_CREATED_AT.trust());
		assertThat(DateSource.FILE_CREATED_AT.trust()).isGreaterThan(DateSource.UNKNOWN.trust());
	}

	/**
	 * A name that carries the time of day was written by whatever produced the
	 * file, so it is worth as much as a day an mtime corroborated - which is
	 * exactly what the refiner declines to touch.
	 */
	@Test
	void aNameThatCarriesTheTimeOfDayIsWorthACorroboratedOne() {
		assertThat(DateSource.trustOf(DateSource.FILE_NAME, COM_HORA))
				.isEqualTo(DateSource.FILE_NAME_CONFIRMED.trust());
	}

	@Test
	void aBareDayFromTheNameStaysBelowACorroboratedOne() {
		assertThat(DateSource.trustOf(DateSource.FILE_NAME, MEIA_NOITE))
				.isLessThan(DateSource.FILE_NAME_CONFIRMED.trust());
	}

	@Test
	void theTimeOfDayOnlyLiftsANameDate() {
		assertThat(DateSource.trustOf(DateSource.FILE_MODIFIED_AT, COM_HORA))
				.isEqualTo(DateSource.FILE_MODIFIED_AT.trust());
		assertThat(DateSource.trustOf(DateSource.EXIF, COM_HORA)).isEqualTo(DateSource.EXIF.trust());
	}

	@Test
	void anAbsentSourceOrDateTrustsNothingExtra() {
		assertThat(DateSource.trustOf(null)).isZero();
		assertThat(DateSource.trustOf(null, COM_HORA)).isZero();
		assertThat(DateSource.trustOf(DateSource.FILE_NAME, null)).isEqualTo(DateSource.FILE_NAME.trust());
	}
}