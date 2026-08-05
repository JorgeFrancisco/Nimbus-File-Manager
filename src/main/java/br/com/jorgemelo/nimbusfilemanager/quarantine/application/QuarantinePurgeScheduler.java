package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the quarantine retention purge once a day on its own daemon thread,
 * mirroring how the folder watcher schedules its own work (the app has no
 * Spring {@code @EnableScheduling}). The retention window is read fresh from
 * {@link QuarantineRetentionPolicy#retentionDays()} each run, so changing it in
 * Settings takes effect on the next pass. Any non-positive, blank or invalid
 * value disables the purge entirely (fail-safe: a destructive purge never runs
 * on an unreadable retention window); only a positive number of days runs it.
 */
@Slf4j
@Service
@Profile(NimbusProfiles.APP)
public class QuarantinePurgeScheduler {

	/**
	 * Wait a bit after startup so the app finishes booting before the first purge.
	 */
	private static final long INITIAL_DELAY_MINUTES = 5;
	private static final long PERIOD_MINUTES = 24L * 60;

	private final QuarantineRetentionPolicy quarantineRetentionPolicy;
	private final QuarantineLauncherService quarantineLauncherService;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "nimbus-file-manager-quarantine-purge");

		thread.setDaemon(true);

		return thread;
	});
	private volatile boolean shuttingDown;

	public QuarantinePurgeScheduler(QuarantineRetentionPolicy quarantineRetentionPolicy,
			QuarantineLauncherService quarantineLauncherService) {
		this.quarantineRetentionPolicy = quarantineRetentionPolicy;
		this.quarantineLauncherService = quarantineLauncherService;

		executor.scheduleWithFixedDelay(this::runOnce, INITIAL_DELAY_MINUTES, PERIOD_MINUTES, TimeUnit.MINUTES);
	}

	/**
	 * One purge pass. Package-private so it can be exercised directly in tests
	 * without the scheduler.
	 */
	final void runOnce() {
		try {
			// The window (and its fail-safe for a blank/invalid setting) belongs to the
			// retention policy; this class only decides when to ask.
			int days = quarantineRetentionPolicy.retentionDays();

			if (days <= 0) {
				return;
			}

			// Queued, never run here. Expunging is the most destructive thing this
			// product does and it belongs where the rest of the file work happens; the
			// window travels with the request, and what is overdue is decided when the
			// purge actually runs.
			quarantineLauncherService.launchScheduledPurge(days);
		} catch (Exception e) {
			if (shuttingDown || Thread.currentThread().isInterrupted()) {
				log.debug("Scheduled quarantine purge interrupted during shutdown", e);
			} else {
				log.error("Scheduled quarantine purge failed", e);
			}
		}
	}

	@PreDestroy
	void shutdown() {
		shuttingDown = true;

		executor.shutdownNow();
	}
}