package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.EtaEstimate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.EtaProperties;

/**
 * The one place that decides how much longer a run has to go.
 *
 * <p>
 * <b>Recent mean over a time window, and why not the alternatives.</b> Measured
 * against a real run of 169 fingerprint chunks:
 *
 * <ul>
 * <li>A <em>cumulative</em> mean never forgets. A four-minute interference at
 * the start of a five-hour run was still inflating the estimate hours later,
 * which is the complaint this class exists to answer.</li>
 * <li>A <em>median of recent samples</em>, and any short window, forgets too
 * much: they sit between the environment's periodic stalls and so predict a
 * finish that will not happen. Measured bias -13,6% (median of 7) to -16,8%
 * (median of 11). The stalls are recurring cost, not anomalies to discard - two
 * of them took 18% of the wall clock.</li>
 * <li>A window holding at least one full cycle of that interference came out at
 * +0,1%.</li>
 * </ul>
 *
 * <p>
 * <b>Two points, not a series.</b> The window is carried by an older mark and a
 * newer one on the execution row itself, so it costs no query, no extra write
 * and no time series - and, because it is on the row rather than in a worker's
 * memory, the screen in the application process and the worker doing the work
 * read the same measurement. The effective span settles between one and two
 * windows as the marks roll forward.
 */
@Component
public class EtaEstimator {

	private final ExecutionProgressModels models;
	private final ExecutionProgressReader progress;
	private final EtaProperties properties;
	private final Clock clock;

	public EtaEstimator(ExecutionProgressModels models, ExecutionProgressReader progress, EtaProperties properties,
			Clock clock) {
		this.models = models;
		this.progress = progress;
		this.properties = properties;
		this.clock = clock;
	}

	public EtaEstimate estimate(Execution execution) {
		if (execution == null || !underWay(execution)) {
			return EtaEstimate.notApplicable();
		}

		if (!models.modelFor(execution.getExecutionType()).etaApplicable()) {
			return EtaEstimate.notApplicable();
		}

		Long total = progress.total(execution);

		if (total == null) {
			return EtaEstimate.notApplicable();
		}

		long done = progress.done(execution);

		if (done >= total) {
			return EtaEstimate.of(0);
		}

		return fromWindow(execution, done, total);
	}

	/**
	 * Whether there is work in flight to have a remaining time at all.
	 *
	 * <p>
	 * A run that never started and a run that already ended are both "no", and
	 * neither is "not yet": nothing is measuring them, so waiting will not produce
	 * an estimate. Saying {@code CALCULATING} for either would leave a screen
	 * promising a number that can never arrive - which is exactly what the old
	 * sentinel {@code -1} did, because it could not tell the two apart.
	 */
	private boolean underWay(Execution execution) {
		return execution.getStartedAt() != null && execution.getFinishedAt() == null
				&& (execution.getStatus() == null || !execution.getStatus().isTerminal());
	}

	/**
	 * The rate is what the window actually observed: units gained since the older
	 * mark, over the time since it. A run that has not yet measured enough says so
	 * rather than dividing by a span too short to mean anything.
	 */
	private EtaEstimate fromWindow(Execution execution, long done, long total) {
		LocalDateTime from = execution.getRateWindowFromAt();

		if (from == null) {
			return EtaEstimate.calculating();
		}

		return estimate(done, total, done - value(execution.getRateWindowFromDone()),
				Duration.between(instantOf(from), clock.instant()).toMillis());
	}

	/**
	 * The same arithmetic, over a window the caller measured itself.
	 *
	 * <p>
	 * For work that has an execution row the window is on the row; a download
	 * carries its own, because it is a step of a few seconds in one process and
	 * inventing a row for it would be bookkeeping nobody reads. What must not
	 * differ is this - the rate, the guard against too short a sample, and the
	 * precision the answer is allowed to claim.
	 *
	 * @param gained units concluded inside the window
	 * @param spanMillis how long the window covers
	 */
	public EtaEstimate estimate(long done, long total, long gained, long spanMillis) {
		if (total <= 0) {
			return EtaEstimate.notApplicable();
		}

		if (done >= total) {
			return EtaEstimate.of(0);
		}

		if (spanMillis < properties.minimumSpanMillis() || gained <= 0) {
			return EtaEstimate.calculating();
		}

		return EtaEstimate.of(rounded((total - done) * spanMillis / gained / 1000));
	}

	/**
	 * Rounded to what the measurement can actually support, before anybody words
	 * it.
	 *
	 * <p>
	 * The rounding belongs here and not in the interface because it is a statement
	 * about the estimate rather than about the language: measured against a real
	 * run, the best of the estimators predicted the next few minutes to within
	 * 20-25%. Announcing "4 h 56 min" over that error claims a minute of precision
	 * the number does not have, and a reader who watches it drift concludes the
	 * whole figure is untrustworthy - which it is, at that precision.
	 *
	 * <p>
	 * Each band keeps roughly a tenth of its horizon, half the measured error.
	 */
	private long rounded(long seconds) {
		if (seconds < 60) {
			return seconds;
		}

		if (seconds < 600) {
			return round(seconds, 60);
		}

		if (seconds < 3_600) {
			return round(seconds, 300);
		}

		return seconds < 14_400 ? round(seconds, 1_800) : round(seconds, 3_600);
	}

	private long round(long seconds, long step) {
		return Math.max(step, Math.round(seconds / (double) step) * step);
	}

	private Instant instantOf(LocalDateTime at) {
		return at.atZone(clock.getZone()).toInstant();
	}

	private long value(Integer count) {
		return count == null ? 0 : count;
	}
}