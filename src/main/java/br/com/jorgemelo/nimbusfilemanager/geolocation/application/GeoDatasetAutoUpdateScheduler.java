package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.BoundaryDatasetProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps the offline geographic dataset current without asking anyone. Whoever
 * runs this application has no way to know whether they will need a boundary
 * file, so the dataset is acquired when the feature is on and missing, and
 * checked once a day after that.
 *
 * <p>
 * The daily pass is "after the configured time, if it has not run today" rather
 * than a wall-clock alarm: on a personal machine the computer is usually off at
 * four in the morning, and an alarm that fires into a sleeping machine simply
 * never runs. This way the check happens at the configured time on a machine
 * that is up, and at the first opportunity of the day on one that is not.
 *
 * <p>
 * The marker of "already ran today" is deliberately in memory: after a restart
 * the pass runs once more, which costs three conditional requests and no bytes
 * when the server has nothing new. Persisting it would buy nothing and add a
 * file to keep consistent.
 */
@Slf4j
@Service
public class GeoDatasetAutoUpdateScheduler {

	/** Enough to notice the configured time without polling hard. */
	private static final long TICK_SECONDS = 60;
	private static final long INITIAL_DELAY_SECONDS = 60;
	private static final String DEFAULT_TIME = "04:00";

	private final AppSettingService appSettingService;
	private final OfflineGeoDataset offlineGeoDataset;
	private final GeoDatasetAsyncRunner geoDatasetAsyncRunner;
	private final InventoryRunningState inventoryRunningState;
	private final Clock clock;

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "nimbus-file-manager-geo-auto-update");

		thread.setDaemon(true);

		return thread;
	});

	private volatile LocalDate lastCheckedOn;

	public GeoDatasetAutoUpdateScheduler(AppSettingService appSettingService, OfflineGeoDataset offlineGeoDataset,
			GeoDatasetAsyncRunner geoDatasetAsyncRunner, InventoryRunningState inventoryRunningState, Clock clock,
			BoundaryDatasetProperties properties) {
		this.appSettingService = appSettingService;
		this.offlineGeoDataset = offlineGeoDataset;
		this.geoDatasetAsyncRunner = geoDatasetAsyncRunner;
		this.inventoryRunningState = inventoryRunningState;
		this.clock = clock;

		if (properties.isAutoUpdate()) {
			executor.scheduleWithFixedDelay(this::runOnce, INITIAL_DELAY_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
		}
	}

	/**
	 * Stops the timer with the context that owns it. Without this the thread
	 * outlives its own beans: a pass firing after the context closed reaches a
	 * connection pool that is already shut, and reports it as a failed update
	 * rather than as what it is. A suite that opens dozens of contexts accumulates
	 * one live scheduler per context, all of them still ticking against pools
	 * nobody can use.
	 */
	@PreDestroy
	public void stop() {
		executor.shutdownNow();
	}

	/**
	 * One decision pass. Package-private so the decision can be exercised directly,
	 * without waiting on the timer.
	 */
	final void runOnce() {
		try {
			if (!shouldRun()) {
				return;
			}

			if (!geoDatasetAsyncRunner.start()) {
				return;
			}

			lastCheckedOn = LocalDate.now(clock);

			geoDatasetAsyncRunner.downloadAndImport();
		} catch (Exception e) {
			// A failed pass must never kill the timer: the next tick tries again.
			log.warn("Geographic dataset auto-update pass failed", e);
		}
	}

	private boolean shouldRun() {
		if (!appSettingService.booleanValue(SettingsConstants.LOCATION_ENABLED, false)) {
			return false;
		}

		// An inventory reads locations while it runs; replacing the dataset under it
		// would change the answers halfway through.
		if (inventoryRunningState.isRunning()) {
			return false;
		}

		// Nothing installed while the feature is on: acquire it now, whatever the
		// hour. This is the case that removes the download from the operator's lap.
		if (!offlineGeoDataset.status().available()) {
			return true;
		}

		return dueToday();
	}

	private boolean dueToday() {
		if (!appSettingService.booleanValue(SettingsConstants.BOUNDARY_AUTO_UPDATE_ENABLED, true)) {
			return false;
		}

		LocalDate today = LocalDate.now(clock);

		if (today.equals(lastCheckedOn)) {
			return false;
		}

		return !LocalTime.now(clock).isBefore(configuredTime());
	}

	/**
	 * The stored value is canonical {@code HH:mm}; a value that somehow is not
	 * falls back to the seeded default instead of disabling the update silently.
	 */
	private LocalTime configuredTime() {
		String configured = appSettingService.stringValue(SettingsConstants.BOUNDARY_AUTO_UPDATE_TIME, DEFAULT_TIME);

		try {
			return LocalTime.parse(configured);
		} catch (RuntimeException _) {
			return LocalTime.parse(DEFAULT_TIME);
		}
	}
}