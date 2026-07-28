package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * Cleanup of the execution history (and its telemetry). Only finished
 * executions are removed - executions in progress are never deleted. The bulk
 * removal triggers the database FKs (movement / execution_step /
 * execution_phase on CASCADE; execution_error on SET NULL), so no relevant
 * orphan is left behind.
 */
@Service
public class ExecutionRetentionService extends LocalizedComponent {

	private final ExecutionRepository executionRepository;
	private final Clock clock;

	public ExecutionRetentionService(ExecutionRepository executionRepository, Clock clock) {
		this.executionRepository = executionRepository;
		this.clock = clock;
	}

	/**
	 * Deletes executions finished more than {@code days} days ago.
	 * {@code days == 0} removes all finished ones.
	 *
	 * @return number of executions removed
	 */
	@Transactional
	public int deleteOlderThanDays(int days) {
		if (days < 0) {
			throw new IllegalArgumentException(message("backend.retention.daysNegative"));
		}

		LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(days);

		return executionRepository.deleteFinishedBefore(cutoff);
	}

	/**
	 * Keeps only the {@code keep} most recent finished executions and deletes the
	 * rest. {@code keep == 0} removes all finished ones.
	 *
	 * @return number of executions removed
	 */
	@Transactional
	public int keepLatest(int keep) {
		if (keep < 0) {
			throw new IllegalArgumentException(message("backend.retention.keepNegative"));
		}

		if (keep == 0) {
			return executionRepository.deleteAllFinished();
		}

		List<Long> keepIds = executionRepository.findFinishedIdsByStartedAtDesc(PageRequest.of(0, keep));

		if (keepIds.isEmpty()) {
			return 0;
		}

		return executionRepository.deleteFinishedNotIn(keepIds);
	}
}