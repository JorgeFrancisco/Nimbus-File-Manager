package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryBootstrapState;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.BoundaryDatasetProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps the offline geographic dataset current without asking anyone. Whoever
 * runs this application has no way to know whether they will need a boundary
 * file, so the dataset is acquired when the feature is on and missing, and
 * checked once a day after that - once the library has been catalogued for the
 * first time, which is the one thing it waits for. The reason is in
 * {@link #shouldRun()}, beside the condition.
 *
 * <p>
 * The daily pass is "after the configured time, if it has not run today" rather
 * than a wall-clock alarm: on a personal machine the computer is usually off at
 * four in the morning, and an alarm that fires into a sleeping machine simply
 * never runs. This way the check happens at the configured time on a machine
 * that is up, and at the first opportunity of the day on one that is not.
 *
 * <p>
 * The marker of "already ran today" is in memory, and it is a cache rather than
 * the answer. A restart empties it, and for a while that was taken to mean the
 * pass should simply run again - which was defended as costing three conditional
 * requests and no bytes. It did not: the update reimported every boundary
 * whether or not anything had changed, so a restart bought a second full rebuild
 * of the dataset minutes after the first. The durable answer to "has today's
 * check happened?" is the execution history, and it is asked whenever the cache
 * cannot answer - see {@link #dueToday()}. Nothing new is persisted for this: a
 * finished run already records that it finished, and when.
 */
@Slf4j
@Service
@Profile(NimbusProfiles.APP)
public class GeoDatasetAutoUpdateScheduler {

	/** Enough to notice the configured time without polling hard. */
	private static final long TICK_SECONDS = 60;
	private static final long INITIAL_DELAY_SECONDS = 60;
	private static final String DEFAULT_TIME = "04:00";

	private final AppSettingService appSettingService;
	private final OfflineGeoDataset offlineGeoDataset;
	private final GeoLauncher geoLauncher;
	private final GeoRunReader geoRunReader;
	private final InventoryRunningState inventoryRunningState;
	private final InventoryBootstrapState inventoryBootstrapState;
	private final Clock clock;

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "nimbus-file-manager-geo-auto-update");

		thread.setDaemon(true);

		return thread;
	});

	private volatile LocalDate lastCheckedOn;

	public GeoDatasetAutoUpdateScheduler(AppSettingService appSettingService, OfflineGeoDataset offlineGeoDataset,
			GeoLauncher geoLauncher, GeoRunReader geoRunReader, InventoryRunningState inventoryRunningState,
			InventoryBootstrapState inventoryBootstrapState, Clock clock,
			BoundaryDatasetProperties properties) {
		this.appSettingService = appSettingService;
		this.offlineGeoDataset = offlineGeoDataset;
		this.geoLauncher = geoLauncher;
		this.geoRunReader = geoRunReader;
		this.inventoryRunningState = inventoryRunningState;
		this.inventoryBootstrapState = inventoryBootstrapState;
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

			// It asks; it does not do. A second ask joins the row already waiting, so
			// the timer firing beside a click - or beside itself after a restart - costs
			// one query and produces one update.
			if (geoLauncher.updateDataset().isEmpty()) {
				return;
			}

			lastCheckedOn = LocalDate.now(clock);
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

		// One acquisition at a time, and this is the guard that says so - not the
		// deduplication key, which deliberately allows one request to wait while an
		// identical one runs. Asked without it, the branch below reads a dataset that
		// is empty *because* an import is emptying it: the import deletes before it
		// writes, so for the minutes it takes, "nothing installed" is true and this
		// pass asks again every tick. Deduplication then held the damage to a single
		// extra run, which downloaded nothing new, deleted the whole table and
		// rebuilt it - and left the panel showing a finished update beside a running
		// one.
		if (geoRunReader.importRunning()) {
			return false;
		}

		// Nothing installed while the feature is on: acquire it now, whatever the
		// hour - but not before the library has been catalogued once. On a fresh
		// installation this timer fires a minute after boot, when no inventory exists
		// yet for the guard above to yield to, and it would spend the first minutes
		// downloading and importing two hundred thousand polygons to resolve the
		// coordinates of nothing - while the user starts the first walk on top of it.
		// Afterwards the ordinary policy resumes, and the operator still never has to
		// ask for the download. Asking explicitly on the settings screen is not this
		// path and is never held back.
		if (!offlineGeoDataset.status().available()) {
			return inventoryBootstrapState.hasCompletedAtLeastOnce();
		}

		return dueToday();
	}

	/**
	 * <b>Memory first, history when memory is cold.</b> The field answers every
	 * ordinary tick without touching the database, which is what keeps a check
	 * running once a minute from costing a query a minute. What it cannot answer is
	 * the first tick after a restart, and that is the case this exists for: the
	 * field is empty then, while the obligation may well have been discharged an
	 * hour ago by a run this process never saw.
	 *
	 * <p>
	 * So the history is consulted exactly there, and its answer is written back
	 * into the field - not as a shortcut, but because the two now agree and there
	 * is no reason to ask again today.
	 */
	private boolean dueToday() {
		if (!appSettingService.booleanValue(SettingsConstants.BOUNDARY_AUTO_UPDATE_ENABLED, true)) {
			return false;
		}

		LocalDate today = LocalDate.now(clock);

		if (today.equals(lastCheckedOn)) {
			return false;
		}

		if (geoRunReader.completedToday()) {
			lastCheckedOn = today;

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