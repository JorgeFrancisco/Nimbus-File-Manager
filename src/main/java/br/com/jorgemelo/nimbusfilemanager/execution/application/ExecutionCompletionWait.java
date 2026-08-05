package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Waiting a little while for a queued execution to finish, so an interactive
 * screen can answer the way it always did when the work is quick.
 *
 * <p>
 * It observes and nothing else. It does not claim, does not renew a lease, does
 * not own anything and holds no state between calls - the only thing it knows
 * about the execution is what the row says, which is the same thing anybody
 * else would read. That is deliberate: the alternative shape, a registry of
 * futures completed by the worker, would put half of an execution's outcome in
 * one process's heap and make the queue a hint.
 *
 * <p>
 * Running out of budget is not an outcome. Nothing is cancelled, nothing is
 * failed, ownership and lease are untouched, and the execution goes on exactly
 * as it would have: the caller simply stops waiting for it and says so.
 *
 * <p>
 * Not transactional, and it must not be called from inside a transaction. Each
 * look is its own short read, which is what makes the next one able to see a
 * change another process committed; inside one transaction Hibernate would keep
 * answering from the persistence context it loaded first, and the wait would
 * poll a snapshot until the budget ran out.
 */
@Component
public class ExecutionCompletionWait {

	/**
	 * How often to look. Twenty reads by primary key over a one-second budget,
	 * which is nothing next to what the request already costs, and fine-grained
	 * enough that the wait adds no perceptible delay of its own to work that
	 * finishes in milliseconds - which, with the wake-up in place, is the ordinary
	 * case.
	 */
	private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

	private final ExecutionRepository executionRepository;

	public ExecutionCompletionWait(ExecutionRepository executionRepository) {
		this.executionRepository = executionRepository;
	}

	/**
	 * @return the execution once it has reached a terminal state, or empty when the
	 * budget ran out first - which says nothing about the execution except that it
	 * had not finished yet
	 */
	public Optional<Execution> awaitTerminal(long executionId, Duration budget) {
		long deadline = System.nanoTime() + budget.toNanos();

		while (true) {
			Optional<Execution> current = executionRepository.findById(executionId);

			if (current.isEmpty() || current.get().getStatus().isTerminal()) {
				return current;
			}

			long remaining = deadline - System.nanoTime();

			if (remaining <= 0 || !pause(remaining)) {
				return Optional.empty();
			}
		}
	}

	/**
	 * @return false when the wait was interrupted, so the caller stops waiting
	 * rather than swallowing the interruption and going round again
	 */
	private boolean pause(long remainingNanos) {
		try {
			Thread.sleep(Math.clamp(remainingNanos / 1_000_000, 1, POLL_INTERVAL.toMillis()));

			return true;
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();

			return false;
		}
	}
}