package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.time.Clock;

import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.EtaProperties;

/**
 * The progress authority, assembled for a test that only needs it to exist.
 *
 * <p>
 * Built rather than mocked, and deliberately: the models are the declaration of
 * what every workload's counters mean, so a mock of them would let a test agree
 * with an answer production never gives. What is under test in most of these is
 * something else entirely - a launcher, a mapper, a controller - and this is the
 * real thing, cheap enough to hand out.
 */
public final class Progress {

	private Progress() {
	}

	public static ExecutionProgressModels models() {
		return new ExecutionProgressModels();
	}

	public static ExecutionProgressReader reader() {
		return new ExecutionProgressReader(models());
	}

	/** The estimator with the shipped window, over a clock the caller controls. */
	public static EtaEstimator estimator(Clock clock) {
		return new EtaEstimator(models(), reader(), properties(), clock);
	}

	public static EtaEstimator estimator() {
		return estimator(Clock.systemDefaultZone());
	}

	public static ExecutionRateWindow window(Clock clock) {
		return new ExecutionRateWindow(reader(), properties(), clock);
	}

	/** Unset, so the shipped default applies - which is what production runs. */
	public static EtaProperties properties() {
		return new EtaProperties(null);
	}
}