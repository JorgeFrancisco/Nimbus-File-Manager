package br.com.jorgemelo.nimbusfilemanager.metadata.application.date;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;

import org.springframework.stereotype.Component;

@Component
public class CaptureDateValidator {

	private static final int MIN_YEAR = 1995;
	private static final int MAX_FUTURE_YEARS = 1;

	private final Clock clock;

	public CaptureDateValidator(Clock clock) {
		this.clock = clock;
	}

	/**
	 * The same rule for a value that is an instant rather than a local reading -
	 * a filesystem timestamp. The bounds are still expressed in local terms,
	 * because "a year from now" is a human statement; the clock's zone is used to
	 * turn that bound into an instant, and never to reinterpret the value itself.
	 */
	public Instant validate(Instant timestamp) {
		if (timestamp == null) {
			return null;
		}

		LocalDate today = LocalDate.now(clock);

		if (timestamp.isAfter(today.plusYears(MAX_FUTURE_YEARS).atStartOfDay(clock.getZone()).toInstant())) {
			return null;
		}

		if (timestamp.isBefore(LocalDate.of(MIN_YEAR, Month.JANUARY, 1).atStartOfDay(clock.getZone()).toInstant())) {
			return null;
		}

		return timestamp;
	}

	public LocalDateTime validate(LocalDateTime captureDate) {
		if (captureDate == null) {
			return null;
		}

		LocalDate today = LocalDate.now(clock);

		if (captureDate.toLocalDate().isAfter(today.plusYears(MAX_FUTURE_YEARS))) {
			return null;
		}

		if (captureDate.getYear() < MIN_YEAR) {
			return null;
		}

		return captureDate;
	}
}