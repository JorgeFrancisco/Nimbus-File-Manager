package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How far back the remaining-time estimate looks.
 *
 * <p>
 * <b>The window is calibration, not a rule of the domain.</b> The engine is
 * "recent mean over a time window"; how wide that window should be depends on
 * the machine it runs on, so it is a property and not a constant. What is
 * permanent is the criterion that sets it:
 *
 * <blockquote>the window has to span at least one full cycle of whatever
 * recurring interference the environment imposes.</blockquote>
 *
 * <p>
 * <b>Where the default came from.</b> Measured, on a real run of 169 chunks with
 * nothing else competing. The dominant interference there is the database's own
 * periodic checkpoint: it stalls a chunk for around 65 s roughly every 5
 * minutes, and cost 18% of the wall clock from two occurrences alone. A window
 * that excludes it - any short window, and a median of recent samples especially
 * - predicts a finish that will not happen: measured bias was -13,6% to -16,8%.
 * A 5-minute window, one full cycle, came out at +0,1%. Hence the default.
 *
 * <p>
 * Another machine has another cycle. Recalibrate the number; the criterion above
 * is what tells you to what.
 */
@ConfigurationProperties(prefix = "nimbus-file-manager.execution.eta")
public record EtaProperties(Long windowMillis) {

	private static final Logger log = LoggerFactory.getLogger(EtaProperties.class);

	/** One checkpoint cycle on the machine this was measured on. */
	public static final long DEFAULT_WINDOW_MILLIS = 300_000;

	/**
	 * Below this a window cannot contain a recurring stall of any realistic period,
	 * which is the one thing it exists to do.
	 */
	public static final long MIN_WINDOW_MILLIS = 30_000;

	/** Beyond an hour a "recent" mean is no longer recent by any reading. */
	public static final long MAX_WINDOW_MILLIS = 3_600_000;

	public long windowMillisOrDefault() {
		if (windowMillis == null) {
			return DEFAULT_WINDOW_MILLIS;
		}

		if (windowMillis < MIN_WINDOW_MILLIS) {
			log.warn("nimbus-file-manager.execution.eta.window-millis={} is below the minimum {}; using {}.",
					windowMillis, MIN_WINDOW_MILLIS, MIN_WINDOW_MILLIS);

			return MIN_WINDOW_MILLIS;
		}

		if (windowMillis > MAX_WINDOW_MILLIS) {
			log.warn("nimbus-file-manager.execution.eta.window-millis={} is above the maximum {}; using {}.",
					windowMillis, MAX_WINDOW_MILLIS, MAX_WINDOW_MILLIS);

			return MAX_WINDOW_MILLIS;
		}

		return windowMillis;
	}

	/**
	 * How little measurement still answers, expressed against the window so it
	 * recalibrates with it: a tenth of the window is quick enough that a screen is
	 * not left saying "calculating" for minutes, and long enough that a single
	 * durable advance does not get to decide the rate on its own.
	 */
	public long minimumSpanMillis() {
		return Math.max(1, windowMillisOrDefault() / 10);
	}
}