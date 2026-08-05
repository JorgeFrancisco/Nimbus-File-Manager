package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock that moves on its own, one step per reading.
 *
 * <p>
 * The supervisor asks the time twice about the same worker - once when it
 * starts and once when it exits - and everything it then decides comes from the
 * difference. A step of two minutes describes a worker that was working; a step
 * of zero describes one that never got going. Both are a single line here and
 * neither costs the test any waiting.
 */
final class SteppingClock extends Clock {

	private final Duration step;

	private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

	SteppingClock(Duration step) {
		this.step = step;
	}

	@Override
	public Instant instant() {
		Instant current = instant;

		instant = instant.plus(step);

		return current;
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