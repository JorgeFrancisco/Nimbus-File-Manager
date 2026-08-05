package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the test moves by hand.
 *
 * <p>
 * Exists so that expiry can be asserted instead of waited for: a cache that
 * holds an answer for half a second would otherwise need half a second of real
 * sleeping per test, which is both slow and flaky.
 */
final class AdvanceableClock extends Clock {

	private Instant instant;

	AdvanceableClock(Instant instant) {
		this.instant = instant;
	}

	void advance(Duration duration) {
		instant = instant.plus(duration);
	}

	@Override
	public Instant instant() {
		return instant;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}
}