package br.com.jorgemelo.nimbusfilemanager.metadata.application.date;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class CaptureDateValidatorTest {

	@Test
	void validateShouldRejectNullOldAndFarFutureDates() {
		CaptureDateValidator validator = new CaptureDateValidator(Clock.systemDefaultZone());

		LocalDateTime valid = LocalDateTime.of(2024, Month.MAY, 9, 10, 30);

		Assertions.assertThat(validator.validate((LocalDateTime) null)).isNull();
		Assertions.assertThat(validator.validate(LocalDateTime.of(1994, Month.DECEMBER, 31, 23, 59))).isNull();
		Assertions.assertThat(validator.validate(LocalDateTime.now().plusYears(2))).isNull();
		Assertions.assertThat(validator.validate(valid)).isEqualTo(valid);
	}

	/**
	 * The same bounds asked of a moment rather than of a reading. The bound itself
	 * is a human statement - "a year from now" - so the clock's zone turns it into
	 * an instant, and is never used to reinterpret the value being judged.
	 */
	@Test
	void validateShouldRejectNullOldAndFarFutureInstants() {
		CaptureDateValidator validator = new CaptureDateValidator(Clock.systemDefaultZone());

		Instant valid = Instant.parse("2024-05-09T10:30:00Z");

		Assertions.assertThat(validator.validate((Instant) null)).isNull();
		Assertions.assertThat(validator.validate(Instant.parse("1994-12-31T23:59:00Z"))).isNull();
		Assertions.assertThat(validator.validate(Instant.now().plus(Duration.ofDays(2 * 365)))).isNull();
		Assertions.assertThat(validator.validate(valid)).isEqualTo(valid);
	}
}