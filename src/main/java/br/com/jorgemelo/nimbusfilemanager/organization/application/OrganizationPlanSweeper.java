package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.organization.domain.model.OrganizationPlanRecord;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.OrganizationPlanRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Deletes plans that are past their expiry, and only those.
 *
 * <p>
 * A plan is not history. The execution that produced it is - "a preview was
 * asked for" belongs on the executions screen and keeps its retention - so this
 * sweep never touches an execution. It removes the artifact and leaves the fact.
 *
 * <p>
 * It also collects what a worker that died left behind: a plan still BUILDING is
 * unreadable and expires on the same schedule as any other, so the residue needs
 * no rule of its own.
 *
 * <p>
 * Its own daemon thread rather than {@code @Scheduled}, because this application
 * has no {@code @EnableScheduling} - the same arrangement the catalog and
 * quarantine purges use.
 */
@Slf4j
@Service
@Profile(NimbusProfiles.APP)
class OrganizationPlanSweeper {

	/** Long enough after startup that booting is over before the first sweep. */
	private static final long INITIAL_DELAY_MINUTES = 5;
	private static final long PERIOD_MINUTES = 60;

	private final OrganizationPlanRepository planRepository;
	private final Clock clock;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "nimbus-file-manager-organization-plan-sweep");

		thread.setDaemon(true);

		return thread;
	});

	OrganizationPlanSweeper(OrganizationPlanRepository planRepository, Clock clock) {
		this.planRepository = planRepository;
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
	 * every scheduled run and would only look like a guarantee - it carried one
	 * until this was noticed. The transaction that matters is the repository's own,
	 * around the delete.
	 *
	 * <p>
	 * Every failure is swallowed: a scheduled pass that lets an exception escape
	 * kills its own timer for the lifetime of the process, and an expired plan
	 * lingering until the next pass is a smaller problem than never sweeping again.
	 */
	final void runOnce() {
		try {
			List<OrganizationPlanRecord> expired = planRepository.findByExpiresAtBefore(LocalDateTime.now(clock));

			if (expired.isEmpty()) {
				return;
			}

			// The items go with the plan by foreign key, so deleting the header is the
			// whole operation.
			planRepository.deleteAll(expired);

			log.info("Removed {} expired organization plan(s); their executions were left untouched", expired.size());
		} catch (RuntimeException exception) {
			log.warn("Organization plan sweep failed; it will run again next pass", exception);
		}
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}
}