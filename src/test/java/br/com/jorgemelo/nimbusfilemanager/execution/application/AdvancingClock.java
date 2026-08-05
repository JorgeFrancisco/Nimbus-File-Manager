package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock that stands still until a test moves it.
 *
 * <p>
 * What it is for is throttling: whether a write was held back is a question
 * about time having passed, and asking the real clock would be asking how fast
 * the machine happened to be that run. Deliberately not the worker's
 * {@code SteppingClock}, which advances on its own with every reading - here the
 * point is that nothing moves unless the test says so.
 */
final class AdvancingClock extends Clock {

	private Instant instant = Instant.parse("2026-07-12T12:00:00Z");

	void advance(Duration by) {
		instant = instant.plus(by);
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