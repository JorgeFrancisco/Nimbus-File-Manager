package br.com.jorgemelo.nimbusfilemanager.worker.application.constants;

import java.time.Duration;

/**
 * How often a worker says it is alive, and how long that statement is believed.
 *
 * <p>
 * Contract rather than tuning: the worker writes on one of these and the
 * application judges freshness by the other, in different processes and
 * possibly different builds. A window shorter than a few beats would call a
 * worker dead for one slow round; a much longer one would leave a screen saying
 * "processing" for minutes after the process was killed.
 */
public final class WorkerHealthConstants {

	public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);

	/** Three beats and a little: one lost round is not death. */
	public static final Duration FRESH_WITHIN = Duration.ofSeconds(35);

	/**
	 * How long the row of a worker nobody has seen is kept. Long enough that
	 * "when was the last time anything ran?" is answerable after a night, short
	 * enough that the table never becomes history.
	 */
	public static final Duration FORGET_AFTER = Duration.ofDays(2);

	private WorkerHealthConstants() {
	}
}