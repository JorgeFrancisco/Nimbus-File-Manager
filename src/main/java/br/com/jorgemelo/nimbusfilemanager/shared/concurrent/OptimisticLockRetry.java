package br.com.jorgemelo.nimbusfilemanager.shared.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import jakarta.persistence.OptimisticLockException;

/**
 * Bounded retry for idempotent, re-runnable job steps that may hit an
 * optimistic-lock conflict. The policy is deliberately narrow:
 *
 * <ul>
 * <li>Only for <b>idempotent</b> work (inventory, metadata rebuild,
 * geolocation, fingerprint, organization) that re-reads its state on each
 * attempt.</li>
 * <li><b>Never</b> for manual/admin edits - those must surface the conflict so
 * the user reloads and retries consciously.</li>
 * <li>The retry is <b>bounded</b>: after {@code maxAttempts} it rethrows, so a
 * persistent conflict is never hidden behind an infinite loop.</li>
 * </ul>
 *
 * Each attempt must be an independent transaction (e.g. a
 * {@code TransactionTemplate} call), so a rolled-back attempt is followed by a
 * fresh read on the next one.
 */
public final class OptimisticLockRetry {

	private static final Logger log = LoggerFactory.getLogger(OptimisticLockRetry.class);

	private OptimisticLockRetry() {
	}

	/**
	 * Runs {@code attempt} up to {@code maxAttempts} times, retrying only on an
	 * optimistic-lock conflict and rethrowing once the bound is exhausted.
	 *
	 * @param what short label for logs (the operation being retried)
	 * @param maxAttempts total attempts allowed (>= 1)
	 * @param attempt one independent, transactional, idempotent attempt
	 */
	public static void run(String what, int maxAttempts, Runnable attempt) {
		int attempts = 0;

		while (true) {
			attempts++;

			try {
				attempt.run();

				return;
			} catch (ObjectOptimisticLockingFailureException | OptimisticLockException conflict) {
				if (attempts >= maxAttempts) {
					log.warn("Optimistic-lock conflict on '{}' after {} attempt(s); giving up and propagating.", what,
							attempts);

					throw conflict;
				}

				log.info("Optimistic-lock conflict on '{}' (attempt {}/{}); reloading and retrying.", what, attempts,
						maxAttempts);
			}
		}
	}
}