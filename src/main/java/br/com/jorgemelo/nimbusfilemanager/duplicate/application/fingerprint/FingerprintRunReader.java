package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionStatusNames;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ProgressMath;

/**
 * Whether a fingerprint backlog is being drained, and how much longer it looks
 * like taking.
 *
 * <p>
 * Both answers used to be fields of the runner, which worked only for as long as
 * the drain happened in the process being asked. It does not: a screen renders
 * in the application and the work happens in the worker, so the question goes to
 * the row. The answer therefore survives a restart of either side, and is the
 * same answer in two open tabs.
 */
@Service
@Transactional(readOnly = true)
public class FingerprintRunReader {

	private final ExecutionRepository executionRepository;
	private final Clock clock;

	public FingerprintRunReader(ExecutionRepository executionRepository, Clock clock) {
		this.executionRepository = executionRepository;
		this.clock = clock;
	}

	public Optional<Execution> running(ExecutionType type) {
		return executionRepository.findFirstByExecutionTypeAndStatusInOrderByStartedAtDesc(type,
				ExecutionStatusNames.ACTIVE);
	}

	public boolean isRunning(ExecutionType type) {
		return running(type).isPresent();
	}

	/**
	 * Estimated seconds remaining, or -1 when there is nothing to estimate from.
	 *
	 * <p>
	 * Derived from the row rather than from a counter in memory: how long this run
	 * has been going against how much of what it set out to do it has reached.
	 */
	public long etaSeconds(ExecutionType type) {
		return running(type).map(this::etaSeconds).orElse(-1L);
	}

	private long etaSeconds(Execution execution) {
		if (execution.getStartedAt() == null) {
			return -1;
		}

		long elapsed = Duration.between(execution.getStartedAt().atZone(clock.getZone()).toInstant(), clock.instant())
				.toMillis();

		return ProgressMath.etaSeconds(elapsed, value(execution.getFilesAnalyzed()) + value(execution.getErrors()),
				value(execution.getFilesFound()));
	}

	private long value(Integer count) {
		return count == null ? 0 : count;
	}
}