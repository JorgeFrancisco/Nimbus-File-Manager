package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryBootstrapState;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.OfflineGeoDatasetStatus;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.BoundaryDatasetProperties;

/**
 * The decision of when to touch the dataset, which is where the promise of not
 * bothering anyone lives: acquire what is missing, check once a day after the
 * configured time, and never start on top of work already running.
 */
class GeoDatasetAutoUpdateSchedulerTest {

	private static final String TIMER_THREAD = "nimbus-file-manager-geo-auto-update";

	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final OfflineGeoDataset offlineGeoDataset = mock(OfflineGeoDataset.class);
	private final GeoLauncher geoLauncher = mock(GeoLauncher.class);
	private final GeoRunReader geoRunReader = mock(GeoRunReader.class);
	private final InventoryRunningState inventoryRunningState = mock(InventoryRunningState.class);
	private final InventoryBootstrapState inventoryBootstrapState = mock(InventoryBootstrapState.class);

	private static Clock at(String localDateTime) {
		return Clock.fixed(LocalDateTime.parse(localDateTime).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
	}

	/**
	 * Built with the timer off, and every test drives {@code runOnce} directly. A
	 * scheduler that also ticked on its own would race the assertions and outlive
	 * the test, which is what the suite spent whole builds doing.
	 */
	private GeoDatasetAutoUpdateScheduler scheduler(Clock clock) {
		return scheduler(clock, false);
	}

	private GeoDatasetAutoUpdateScheduler scheduler(Clock clock, boolean autoUpdate) {
		BoundaryDatasetProperties properties = new BoundaryDatasetProperties();

		properties.setAutoUpdate(autoUpdate);

		return new GeoDatasetAutoUpdateScheduler(appSettingService, offlineGeoDataset, geoLauncher, geoRunReader,
				inventoryRunningState, inventoryBootstrapState, clock, properties);
	}

	private Set<Thread> timerThreads() {
		return Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.getName().equals(TIMER_THREAD))
				.collect(Collectors.toSet());
	}

	/**
	 * An acquisition already under way is the one state this pass must not answer
	 * with another request. It reads "nothing installed" while an import is running
	 * - the import empties the table before it fills it - so without this guard the
	 * timer asked again every minute, and deduplication turned that into exactly one
	 * extra run: a second download of files the server reported unchanged, a second
	 * delete of the whole table, and a panel showing a finished update beside a
	 * running one.
	 */
	@Test
	void doesNotAskWhileAnUpdateIsAlreadyRunning() {
		when(appSettingService.booleanValue(eq(SettingsConstants.LOCATION_ENABLED), anyBoolean())).thenReturn(true);
		when(geoRunReader.importRunning()).thenReturn(true);

		scheduler(at("2026-08-01T09:15:00")).runOnce();

		verify(geoLauncher, never()).updateDataset();

		// And it never even asked whether the dataset is installed: the answer is
		// meaningless while the thing that installs it is halfway through.
		verify(offlineGeoDataset, never()).status();
	}

	/**
	 * The timer runs on a thread of its own, and that thread is a daemon: a
	 * scheduler waiting out its initial delay must never be the reason a JVM
	 * refuses to exit.
	 */
	@Test
	void schedulesTheTimerOnADaemonThreadOfItsOwn() {
		Set<Thread> before = timerThreads();

		GeoDatasetAutoUpdateScheduler scheduler = scheduler(at("2026-08-02T10:00:00"), true);

		try {
			Set<Thread> started = timerThreads();

			Assertions.assertThat(started).hasSizeGreaterThan(before.size()).allMatch(Thread::isDaemon);
		} finally {
			scheduler.stop();
		}
	}

	/**
	 * Off means no thread at all, rather than a thread that wakes up to decide it
	 * has nothing to do. It is what lets the suite - and a container with no
	 * business on the network - hold this bean without one.
	 */
	@Test
	void schedulesNothingWhenAutoUpdateIsOff() {
		Set<Thread> before = timerThreads();

		scheduler(at("2026-08-02T10:00:00"));

		Assertions.assertThat(timerThreads()).isEqualTo(before);
	}

	private void locationEnabled(boolean enabled) {
		lenient().when(appSettingService.booleanValue(eq(SettingsConstants.LOCATION_ENABLED), anyBoolean()))
				.thenReturn(enabled);
	}

	private void autoUpdate(boolean enabled, String time) {
		lenient().when(appSettingService.booleanValue(eq(SettingsConstants.BOUNDARY_AUTO_UPDATE_ENABLED), anyBoolean()))
				.thenReturn(enabled);

		lenient().when(appSettingService.stringValue(eq(SettingsConstants.BOUNDARY_AUTO_UPDATE_TIME), any()))
				.thenReturn(time);
	}

	private void installed(boolean available) {
		lenient().when(offlineGeoDataset.status())
				.thenReturn(available
						? new OfflineGeoDatasetStatus(true, "v1", 1000, 1L, null, "C:/geo", "geoBoundaries", "CC BY")
						: OfflineGeoDatasetStatus.unavailable("C:/geo"));
	}

	/** Whether this installation has ever finished walking its library. */
	private void libraryCatalogued(boolean catalogued) {
		lenient().when(inventoryBootstrapState.hasCompletedAtLeastOnce()).thenReturn(catalogued);
	}

	/** The case the whole feature exists for: enabled, nothing on disk. */
	@Test
	void acquiresTheDatasetWhenTheFeatureIsOnAndNothingIsInstalled() {
		locationEnabled(true);
		installed(false);
		libraryCatalogued(true);
		scheduler(at("2026-08-01T09:15:00")).runOnce();

		verify(geoLauncher).updateDataset();
	}

	/**
	 * The guard holds the pass back while an acquisition is under way, and not one
	 * moment longer. A run that failed leaves a terminal row, the dataset is still
	 * not installed, and the very next tick asks again - which is the retry this
	 * feature depends on, and the thing a guard written as "one update per day"
	 * would have quietly broken.
	 */
	@Test
	void asksAgainOnceTheRunThatFailedIsOver() {
		locationEnabled(true);
		installed(false);
		libraryCatalogued(true);

		when(geoRunReader.importRunning()).thenReturn(true, false);

		GeoDatasetAutoUpdateScheduler scheduler = scheduler(at("2026-08-01T09:15:00"));

		scheduler.runOnce();

		verify(geoLauncher, never()).updateDataset();

		scheduler.runOnce();

		verify(geoLauncher).updateDataset();
	}

	/**
	 * A fresh installation, which is where this guard earns its place. The timer
	 * fires a minute after boot, the feature is on by default and nothing is
	 * installed - so before this rule existed it began downloading and importing
	 * two hundred thousand polygons to resolve the coordinates of a library
	 * nobody had catalogued yet, and then competed with the first walk.
	 */
	@Test
	void staysQuietUntilTheLibraryHasBeenCataloguedOnce() {
		locationEnabled(true);
		installed(false);
		libraryCatalogued(false);

		scheduler(at("2026-08-01T09:15:00")).runOnce();

		verify(geoLauncher, never()).updateDataset();
	}

	/**
	 * The first walk being under way is not the same question, and both answers
	 * have to be no: one because nothing has completed, the other because
	 * something is running. Asserted together so removing either guard fails.
	 */
	@Test
	void staysQuietWhileTheFirstInventoryIsStillGoing() {
		locationEnabled(true);
		installed(false);
		libraryCatalogued(false);

		when(inventoryRunningState.isRunning()).thenReturn(true);

		scheduler(at("2026-08-01T09:15:00")).runOnce();

		verify(geoLauncher, never()).updateDataset();
	}

	/**
	 * Once the library has been walked, the ordinary policy resumes - including
	 * yielding to a later inventory, which is the guard that was already here.
	 */
	@Test
	void yieldsToALaterInventoryEvenAfterTheLibraryWasCatalogued() {
		locationEnabled(true);
		installed(false);
		libraryCatalogued(true);

		when(inventoryRunningState.isRunning()).thenReturn(true);

		scheduler(at("2026-08-01T09:15:00")).runOnce();

		verify(geoLauncher, never()).updateDataset();
	}

	@Test
	void staysQuietWhileTheFeatureIsOff() {
		locationEnabled(false);

		scheduler(at("2026-08-01T09:15:00")).runOnce();

		verify(geoLauncher, never()).updateDataset();
	}

	/**
	 * An inventory reads locations as it goes; swapping the dataset under it would
	 * change the answers halfway through the same run.
	 */
	@Test
	void staysQuietWhileAnInventoryIsRunning() {
		locationEnabled(true);
		installed(false);
		when(inventoryRunningState.isRunning()).thenReturn(true);

		scheduler(at("2026-08-01T09:15:00")).runOnce();

		verify(geoLauncher, never()).updateDataset();
	}

	/**
	 * The daily rule, at its three moments: before the configured time nothing
	 * happens; at it the check runs; and later in the day it still runs, which is
	 * what a machine that was switched off at four in the morning depends on.
	 */
	@ParameterizedTest
	@CsvSource({ "2026-08-01T03:59:00, false", "2026-08-01T04:00:00, true", "2026-08-01T14:20:00, true" })
	void checksOnceTheConfiguredTimeHasPassed(String now, boolean expected) {
		locationEnabled(true);
		installed(true);
		autoUpdate(true, "04:00");

		scheduler(at(now)).runOnce();

		verify(geoLauncher, times(expected ? 1 : 0)).updateDataset();
	}

	@Test
	void checksOnlyOncePerDay() {
		locationEnabled(true);
		installed(true);
		autoUpdate(true, "04:00");
		when(geoLauncher.updateDataset()).thenReturn(Optional.of(new Execution()));

		GeoDatasetAutoUpdateScheduler scheduler = scheduler(at("2026-08-01T04:10:00"));

		scheduler.runOnce();
		scheduler.runOnce();

		verify(geoLauncher, times(1)).updateDataset();
	}

	@Test
	void neverUpdatesWhenTheDailyCheckIsTurnedOff() {
		locationEnabled(true);
		installed(true);
		autoUpdate(false, "04:00");

		scheduler(at("2026-08-01T23:00:00")).runOnce();

		verify(geoLauncher, never()).updateDataset();
	}

	/**
	 * An unreadable time must not silently stop the updates: it falls back to the
	 * seeded default, so the dataset still gets its daily check.
	 */
	@Test
	void fallsBackToTheDefaultTimeWhenTheStoredValueIsNotATime() {
		locationEnabled(true);
		installed(true);
		autoUpdate(true, "whenever");
		scheduler(at("2026-08-01T05:00:00")).runOnce();

		verify(geoLauncher).updateDataset();
	}

	/**
	 * The mark of "already checked today" only moves when a request was really
	 * written. A pass that reached a closing application must not consume the day,
	 * or the restart would find nothing to do until tomorrow.
	 */
	@Test
	void doesNotConsumeTheDayWhenNothingWasQueued() {
		locationEnabled(true);
		installed(true);
		autoUpdate(true, "04:00");
		when(geoLauncher.updateDataset()).thenReturn(Optional.empty()).thenReturn(Optional.of(new Execution()));

		GeoDatasetAutoUpdateScheduler scheduler = scheduler(at("2026-08-01T04:10:00"));

		scheduler.runOnce();
		scheduler.runOnce();

		verify(geoLauncher, times(2)).updateDataset();
	}

	/** The timer must survive a failing pass and try again on the next tick. */
	@Test
	void survivesAFailingPass() {
		locationEnabled(true);
		when(offlineGeoDataset.status()).thenThrow(new IllegalStateException("dataset unreadable"));

		GeoDatasetAutoUpdateScheduler scheduler = scheduler(
				Clock.fixed(Instant.parse("2026-08-01T09:15:00Z"), ZoneId.of("UTC")));

		scheduler.runOnce();

		verify(geoLauncher, never()).updateDataset();
	}

	/**
	 * The restart case, and the reason the daily marker stopped being only a field.
	 *
	 * <p>
	 * A run finished this morning; the process was restarted since, so the marker
	 * is empty. Asking again was defended as costing three conditional requests -
	 * and it did not: the update rebuilt every boundary whether or not anything had
	 * changed, so a restart bought a second full reimport minutes after the first.
	 * The history knows what the field forgot.
	 */
	@Test
	void doesNotAskAgainAfterARestartWhenTodayIsAlreadyDischarged() {
		locationEnabled(true);
		installed(true);
		autoUpdate(true, "04:00");

		when(geoRunReader.completedToday()).thenReturn(true);

		scheduler(at("2026-08-01T09:15:00")).runOnce();

		verify(geoLauncher, never()).updateDataset();
	}

	/** Having been discharged yesterday discharges nothing today. */
	@Test
	void asksWhenTheLastCompletedRunWasNotToday() {
		locationEnabled(true);
		installed(true);
		autoUpdate(true, "04:00");

		when(geoRunReader.completedToday()).thenReturn(false);

		scheduler(at("2026-08-01T09:15:00")).runOnce();

		verify(geoLauncher).updateDataset();
	}

	/**
	 * Once the history has answered, the field carries the rest of the day: the
	 * pass runs every minute, and asking the database every minute for something
	 * that cannot change until midnight would be paying for the same answer over
	 * and over.
	 */
	@Test
	void readsTheHistoryOnceAndThenAnswersFromMemory() {
		locationEnabled(true);
		installed(true);
		autoUpdate(true, "04:00");

		when(geoRunReader.completedToday()).thenReturn(true);

		GeoDatasetAutoUpdateScheduler scheduler = scheduler(at("2026-08-01T09:15:00"));

		scheduler.runOnce();
		scheduler.runOnce();
		scheduler.runOnce();

		verify(geoRunReader, times(1)).completedToday();
	}

	/**
	 * A new day is a new obligation, whatever yesterday's history says - the field
	 * holds a date rather than a flag precisely so this cannot be forgotten.
	 */
	@Test
	void asksAgainOnTheNextDay() {
		locationEnabled(true);
		installed(true);
		autoUpdate(true, "04:00");

		when(geoRunReader.completedToday()).thenReturn(true, false);

		scheduler(at("2026-08-01T09:15:00")).runOnce();

		scheduler(at("2026-08-02T09:15:00")).runOnce();

		verify(geoLauncher).updateDataset();
	}
}