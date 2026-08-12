package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.EtaProperties;

/**
 * Keeps the window the remaining time is measured over rolling forward.
 *
 * <p>
 * <b>It rides on a write that was happening anyway.</b> Called from inside the
 * progress update, so the two marks travel in the same statement as the counters
 * and cost no write of their own - which matters, because a fingerprint run
 * issues one of those per chunk and there are thousands of chunks. Nothing here
 * polls, and nothing here writes on a screen's behalf: a mark moves only when
 * real work advanced.
 */
@Component
public class ExecutionRateWindow {

	private final ExecutionProgressReader progress;
	private final EtaProperties properties;
	private final Clock clock;

	public ExecutionRateWindow(ExecutionProgressReader progress, EtaProperties properties, Clock clock) {
		this.progress = progress;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Records where this execution had got to, promoting the younger mark once it
	 * has aged past the window.
	 *
	 * <p>
	 * Call after the counters have been set, since the count recorded is the one
	 * the row now carries.
	 */
	public void advance(Execution execution) {
		LocalDateTime now = LocalDateTime.now(clock);

		int done = (int) Math.min(Integer.MAX_VALUE, progress.done(execution));

		if (execution.getRateWindowFromAt() == null) {
			execution.setRateWindowFromAt(now);
			execution.setRateWindowFromDone(done);
			execution.setRateWindowMarkAt(now);
			execution.setRateWindowMarkDone(done);

			return;
		}

		if (aged(execution.getRateWindowMarkAt(), now)) {
			// The younger mark becomes the older one: the span being measured drops from
			// two windows to one instead of to zero, which is the whole reason there are
			// two of them.
			execution.setRateWindowFromAt(execution.getRateWindowMarkAt());
			execution.setRateWindowFromDone(execution.getRateWindowMarkDone());

			execution.setRateWindowMarkAt(now);
			execution.setRateWindowMarkDone(done);
		}
	}

	/** Forgets the measurement, because whatever comes next measures itself. */
	public void clear(Execution execution) {
		execution.setRateWindowFromAt(null);
		execution.setRateWindowFromDone(null);
		execution.setRateWindowMarkAt(null);
		execution.setRateWindowMarkDone(null);
	}

	private boolean aged(LocalDateTime mark, LocalDateTime now) {
		return mark == null || Duration.between(mark, now).toMillis() >= properties.windowMillisOrDefault();
	}
}