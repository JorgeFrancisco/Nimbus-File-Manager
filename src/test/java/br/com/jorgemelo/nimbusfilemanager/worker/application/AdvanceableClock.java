package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock that moves only when a test says so.
 *
 * <p>
 * Unlike {@link SteppingClock}, which advances on every reading and is meant
 * for code that measures elapsed time, this one stands still. Recovery is about
 * a deadline passing, so the test has to be able to put that moment exactly
 * where it wants it - before a pass and after another - rather than sleep and
 * hope. Nothing here waits, and no assertion depends on how fast the machine
 * running it is.
 */
final class AdvanceableClock extends Clock {

	private final ZoneId zone;

	private Instant instant;

	AdvanceableClock(Instant start, ZoneId zone) {
		this.instant = start;
		this.zone = zone;
	}

	void advance(Duration by) {
		instant = instant.plus(by);
	}

	@Override
	public Instant instant() {
		return instant;
	}

	@Override
	public ZoneId getZone() {
		return zone;
	}

	@Override
	public Clock withZone(ZoneId other) {
		return new AdvanceableClock(instant, other);
	}
}