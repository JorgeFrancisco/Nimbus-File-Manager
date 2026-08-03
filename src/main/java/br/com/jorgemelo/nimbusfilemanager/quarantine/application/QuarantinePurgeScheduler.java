package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the quarantine retention purge once a day on its own daemon thread,
 * mirroring how the folder watcher schedules its own work (the app has no
 * Spring {@code @EnableScheduling}). The retention window is read fresh from
 * {@link QuarantinePurgeService#retentionDays()} each run, so changing it in
 * Settings takes effect on the next pass. Any non-positive, blank or invalid
 * value disables the purge entirely (fail-safe: a destructive purge never runs
 * on an unreadable retention window); only a positive number of days runs it.
 */
@Slf4j
@Service
public class QuarantinePurgeScheduler {

	/**
	 * Wait a bit after startup so the app finishes booting before the first purge.
	 */
	private static final long INITIAL_DELAY_MINUTES = 5;
	private static final long PERIOD_MINUTES = 24L * 60;

	private final QuarantinePurgeService quarantinePurgeService;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "nimbus-file-manager-quarantine-purge");

		thread.setDaemon(true);

		return thread;
	});
	private volatile boolean shuttingDown;

	public QuarantinePurgeScheduler(QuarantinePurgeService quarantinePurgeService) {
		this.quarantinePurgeService = quarantinePurgeService;

		executor.scheduleWithFixedDelay(this::runOnce, INITIAL_DELAY_MINUTES, PERIOD_MINUTES, TimeUnit.MINUTES);
	}

	/**
	 * One purge pass. Package-private so it can be exercised directly in tests
	 * without the scheduler.
	 */
	final void runOnce() {
		try {
			// The window (and its fail-safe for a blank/invalid setting) belongs to the
			// purge itself; this class only decides when to run.
			int days = quarantinePurgeService.retentionDays();

			if (days <= 0) {
				return;
			}

			quarantinePurgeService.purgeOlderThan(days);
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