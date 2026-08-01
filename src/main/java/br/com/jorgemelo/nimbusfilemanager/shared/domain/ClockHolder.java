package br.com.jorgemelo.nimbusfilemanager.shared.domain;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Static bridge that gives JPA entity callbacks
 * ({@code @PrePersist}/{@code @PreUpdate}) access to the application
 * {@link Clock}, since those callbacks cannot receive Spring injection. It is
 * initialized once at startup by {@code ClockHolderInitializer} with the
 * {@code ConfigurableClock}. Restricted to entity callbacks on purpose: Spring
 * components must inject {@link Clock} directly and never route time access
 * through here.
 *
 * <p>
 * Before Spring wires it (and in unit tests that build entities without a
 * context) it falls back to the system clock in the default zone, so entities
 * are always usable.
 */
public final class ClockHolder {

	private static final String BOOTSTRAP_ZONE = "America/Sao_Paulo";

	private static final Clock BOOTSTRAP_CLOCK = Clock.system(ZoneId.of(BOOTSTRAP_ZONE));

	private static volatile Clock clock = BOOTSTRAP_CLOCK;

	private ClockHolder() {
	}

	public static Clock clock() {
		return clock;
	}

	/**
	 * Installs the application clock. Only the startup initializer
	 * ({@code ClockHolderInitializer}) should call this; every other component must
	 * inject {@link Clock} directly.
	 */
	public static void configure(Clock replacement) {
		clock = replacement != null ? replacement : BOOTSTRAP_CLOCK;
	}

	public static void reset() {
		clock = BOOTSTRAP_CLOCK;
	}
}