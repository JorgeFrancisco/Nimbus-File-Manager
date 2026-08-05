package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.EligibilityChanged;
import lombok.extern.slf4j.Slf4j;

/**
 * The single place where a change in who may be analysed turns into a request
 * to regroup.
 *
 * <p>
 * It is one listener on purpose. The operations that change eligibility are
 * spread over quarantine, explorer, catalog, organization and the exclusion
 * screen, and having each of them ask for a regroup would be the coupling this
 * event exists to avoid - and would make "did anything ask?" a question with
 * eight answers.
 *
 * <p>
 * <b>Why {@code AFTER_COMMIT}.</b> The event describes a fact, and a fact that
 * is still inside an open transaction is an intention. Reacting before the
 * commit would queue a regroup for an exclusion that then rolled back, and the
 * analysis it produced would be about a world nobody ever saw.
 *
 * <p>
 * <b>Why {@code fallbackExecution = true}.</b> Some publishers have no
 * transaction to commit, deliberately: quarantine intake moves the file on disk
 * before it writes the catalog and must not hold a transaction across that.
 * Outside a transaction Spring <em>discards</em> a transactional listener's
 * event silently, so without this flag the single most important publisher -
 * the one path every soft delete in the product goes through - would be the one
 * that never fired. With it, the same listener waits for the commit when there
 * is one and runs immediately when there is not, which is exactly right: in
 * both cases what it receives has already happened.
 *
 * <p>
 * <b>Why a failure here is only a warning.</b> The mutation is already
 * committed and is not in question; asking for a refresh is what failed. Letting
 * that escape would report a quarantine that did happen as a quarantine that did
 * not. The safety net is the composition digest: a regroup that was never queued
 * leaves the published grouping's digest differing from the current one, the
 * screen goes on showing the result as outdated, and the user can still ask for
 * a full rebuild. That is why this is best-effort by design and why no outbox is
 * needed to make it more than that.
 */
@Slf4j
@Component
class SimilarityEligibilityRefresh {

	private final SimilarityLauncher similarityLauncher;

	SimilarityEligibilityRefresh(SimilarityLauncher similarityLauncher) {
		this.similarityLauncher = similarityLauncher;
	}

	/**
	 * <b>{@code REQUIRES_NEW} is not decoration.</b> After a commit the
	 * transaction is finished but still bound to the thread, so anything that asks
	 * for the usual propagation joins a transaction that can no longer write - and
	 * queueing the regroup fails with "no transaction is in progress" while the
	 * exclusion that triggered it sits committed. Running after a commit means
	 * owning the next transaction rather than borrowing the last one.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void onEligibilityChanged(EligibilityChanged changed) {
		try {
			similarityLauncher.refreshAfterEligibilityChange();
		} catch (RuntimeException exception) {
			log.warn("Could not request a similarity regroup after {}; the published result stays flagged as "
					+ "outdated until one is asked for.", changed.origin(), exception);
		}
	}
}