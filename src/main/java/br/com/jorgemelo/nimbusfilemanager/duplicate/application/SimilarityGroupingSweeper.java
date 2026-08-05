package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGrouping;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityGroupingRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Collects the analyses that were written down and never became the answer.
 *
 * <p>
 * A grouping left {@code BUILDING} is unreadable by construction - every query a
 * screen makes asks for {@code ACTIVE} - so it is neither a result nor history.
 * It is residue, and the only thing to do with residue is delete it. Deleting
 * the header is the whole operation: {@code similarity_group} and
 * {@code similarity_group_member} follow it out by foreign key.
 *
 * <p>
 * <b>One collector for every way of being abandoned.</b> A worker that died
 * leaves one; so does an incremental result that was overtaken, because
 * {@code SimilarityPublisher.publishIfStillBasedOn} deliberately steps aside
 * rather than replacing a newer answer. Cleaning up at the moment of refusal
 * would put a delete of tens of thousands of rows inside the short transaction
 * the publication exists to keep short - so both cases wait here instead, under
 * one rule.
 *
 * <p>
 * <b>Why the delay is safe, and why it is a day anyway.</b> The grouping and all
 * of its rows are written in one transaction, so a {@code BUILDING} row is only
 * <em>visible</em> once it is <em>complete</em>, and the promotion is the
 * statement immediately after. Anything still unpublished minutes later is
 * therefore abandoned. The window is a day regardless, because the cost of
 * waiting is a few megabytes nobody can see, while the cost of being wrong is an
 * analysis that took minutes - and a desktop that suspends between the two
 * statements can make any shorter reasoning wrong.
 *
 * <p>
 * {@code ACTIVE} is the answer and {@code SUPERSEDED} is history with its own
 * retention; this touches neither, and the query it asks is what makes that true
 * rather than merely intended.
 *
 * <p>
 * Its own daemon thread rather than {@code @Scheduled}, because this application
 * has no {@code @EnableScheduling} - the same arrangement the catalog, the
 * quarantine and the organization-plan sweeps use.
 */
@Slf4j
@Service
@Profile(NimbusProfiles.APP)
class SimilarityGroupingSweeper {

	/** Long enough after startup that booting is over before the first sweep. */
	private static final long INITIAL_DELAY_MINUTES = 5;
	private static final long PERIOD_MINUTES = 60;

	/** How long a grouping may sit unpublished before it is taken as abandoned. */
	private static final long ABANDONED_AFTER_HOURS = 24;

	private final SimilarityGroupingRepository groupingRepository;
	private final Clock clock;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "nimbus-file-manager-similarity-grouping-sweep");

		thread.setDaemon(true);

		return thread;
	});

	SimilarityGroupingSweeper(SimilarityGroupingRepository groupingRepository, Clock clock) {
		this.groupingRepository = groupingRepository;
		this.clock = clock;

		executor.scheduleWithFixedDelay(this::runOnce, INITIAL_DELAY_MINUTES, PERIOD_MINUTES, TimeUnit.MINUTES);
	}

	/**
	 * One pass. Package-private so it can be exercised directly instead of by
	 * waiting an hour.
	 *
	 * <p>
	 * Deliberately not {@code @Transactional}. The timer holds a reference to this
	 * object and not to the Spring proxy, so an annotation here would be inert on
	 * every scheduled run and would only look like a guarantee. The transaction
	 * that matters is the repository's own, around the delete.
	 *
	 * <p>
	 * Every failure is swallowed: a scheduled pass that lets an exception escape
	 * cancels its own timer for the lifetime of the process, and residue lingering
	 * until the next hour is a far smaller problem than never sweeping again.
	 */
	final void runOnce() {
		try {
			LocalDateTime abandonedBefore = LocalDateTime.now(clock).minusHours(ABANDONED_AFTER_HOURS);

			List<SimilarityGrouping> abandoned = groupingRepository
					.findByStatusAndComputedAtBefore(GroupingStatus.BUILDING, abandonedBefore);

			if (abandoned.isEmpty()) {
				return;
			}

			groupingRepository.deleteAll(abandoned);

			log.info("Removed {} similarity grouping(s) that were built and never published, the oldest from before"
					+ " {}; their groups and members went with them", abandoned.size(), abandonedBefore);
		} catch (RuntimeException exception) {
			log.warn("Similarity grouping sweep failed; it will run again next pass", exception);
		}
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}
}