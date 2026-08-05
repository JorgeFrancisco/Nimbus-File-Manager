package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Asks for the catalog missing-record purge once a day on its own daemon
 * thread, mirroring the quarantine purge (the app has no Spring
 * {@code @EnableScheduling}). The retention window is read fresh from
 * {@link SettingsConstants#CATALOG_MISSING_RETENTION_DAYS} each run, so
 * changing it in Settings takes effect on the next pass. Any non-positive,
 * blank or invalid value disables the purge entirely (fail-safe: a destructive
 * purge never runs on an unreadable retention window); only a positive number
 * of days runs it.
 */
@Slf4j
@Service
@Profile(NimbusProfiles.APP)
class CatalogFilePurgeScheduler {

	/**
	 * Wait a bit after startup so the app finishes booting before the first purge.
	 */
	private static final long INITIAL_DELAY_MINUTES = 5;
	private static final long PERIOD_MINUTES = 24L * 60;

	private final AppSettingService appSettingService;
	private final CatalogPurgeLauncherService catalogPurgeLauncherService;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "nimbus-file-manager-catalog-purge");

		thread.setDaemon(true);

		return thread;
	});
	private volatile boolean shuttingDown;

	CatalogFilePurgeScheduler(AppSettingService appSettingService,
			CatalogPurgeLauncherService catalogPurgeLauncherService) {
		this.appSettingService = appSettingService;
		this.catalogPurgeLauncherService = catalogPurgeLauncherService;

		executor.scheduleWithFixedDelay(this::runOnce, INITIAL_DELAY_MINUTES, PERIOD_MINUTES, TimeUnit.MINUTES);
	}

	/**
	 * One purge pass. Package-private so it can be exercised directly in tests
	 * without the scheduler.
	 */
	final void runOnce() {
		try {
			// The fallback is deliberately non-positive (never the product default): a
			// blank or invalid retention setting resolves to it, so an unreadable window
			// disables the destructive purge instead of silently purging with a guess.
			int days = appSettingService.intValue(SettingsConstants.CATALOG_MISSING_RETENTION_DAYS, -1);

			if (days <= 0) {
				return;
			}

			// Queued, never run here. Removing catalog rows is workload like any other,
			// and the timer's job ends at asking for it - which it only does when there
			// is something to ask about, so a quiet day leaves no row behind.
			catalogPurgeLauncherService.launch(days);
		} catch (Exception e) {
			if (shuttingDown || Thread.currentThread().isInterrupted()) {
				log.debug("Scheduled catalog missing purge interrupted during shutdown", e);
			} else {
				log.error("Scheduled catalog missing purge failed", e);
			}
		}
	}

	@PreDestroy
	void shutdown() {
		shuttingDown = true;

		executor.shutdownNow();
	}
}