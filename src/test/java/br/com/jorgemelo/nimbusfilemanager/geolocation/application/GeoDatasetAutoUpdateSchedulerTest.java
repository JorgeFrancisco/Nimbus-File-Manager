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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.OfflineGeoDatasetStatus;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;

/**
 * The decision of when to touch the dataset, which is where the promise of not
 * bothering anyone lives: acquire what is missing, check once a day after the
 * configured time, and never start on top of work already running.
 */
class GeoDatasetAutoUpdateSchedulerTest {

	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final OfflineGeoDataset offlineGeoDataset = mock(OfflineGeoDataset.class);
	private final GeoDatasetAsyncRunner runner = mock(GeoDatasetAsyncRunner.class);
	private final InventoryRunningState inventoryRunningState = mock(InventoryRunningState.class);

	private static Clock at(String localDateTime) {
		return Clock.fixed(LocalDateTime.parse(localDateTime).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
	}

	private GeoDatasetAutoUpdateScheduler scheduler(Clock clock) {
		return new GeoDatasetAutoUpdateScheduler(appSettingService, offlineGeoDataset, runner, inventoryRunningState,
				clock);
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
		lenient().when(offlineGeoDataset.status()).thenReturn(available
				? new OfflineGeoDatasetStatus(true, "v1", 1000, 1L, null, null, "C:/geo", null, "geoBoundaries", "CC BY")
				: OfflineGeoDatasetStatus.unavailable("C:/geo", null));
	}

	/** The case the whole feature exists for: enabled, nothing on disk. */
	@Test
	void acquiresTheDatasetWhenTheFeatureIsOnAndNothingIsInstalled() {
		locationEnabled(true);
		installed(false);
		when(runner.start()).thenReturn(true);

		scheduler(at("2026-08-01T09:15:00")).runOnce();

		verify(runner).downloadAndImport();
	}

	@Test
	void staysQuietWhileTheFeatureIsOff() {
		locationEnabled(false);

		scheduler(at("2026-08-01T09:15:00")).runOnce();

		verify(runner, never()).start();
		verify(runner, never()).downloadAndImport();
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

		verify(runner, never()).downloadAndImport();
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
		lenient().when(runner.start()).thenReturn(true);

		scheduler(at(now)).runOnce();

		verify(runner, times(expected ? 1 : 0)).downloadAndImport();
	}

	@Test
	void checksOnlyOncePerDay() {
		locationEnabled(true);
		installed(true);
		autoUpdate(true, "04:00");
		when(runner.start()).thenReturn(true);

		GeoDatasetAutoUpdateScheduler scheduler = scheduler(at("2026-08-01T04:10:00"));

		scheduler.runOnce();
		scheduler.runOnce();

		verify(runner, times(1)).downloadAndImport();
	}

	@Test
	void neverUpdatesWhenTheDailyCheckIsTurnedOff() {
		locationEnabled(true);
		installed(true);
		autoUpdate(false, "04:00");

		scheduler(at("2026-08-01T23:00:00")).runOnce();

		verify(runner, never()).downloadAndImport();
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
		when(runner.start()).thenReturn(true);

		scheduler(at("2026-08-01T05:00:00")).runOnce();

		verify(runner).downloadAndImport();
	}

	/** A manual update already in flight owns the dataset; do not race it. */
	@Test
	void staysQuietWhenAnUpdateIsAlreadyRunning() {
		locationEnabled(true);
		installed(false);
		when(runner.start()).thenReturn(false);

		scheduler(at("2026-08-01T09:15:00")).runOnce();

		verify(runner, never()).downloadAndImport();
	}

	/** The timer must survive a failing pass and try again on the next tick. */
	@Test
	void survivesAFailingPass() {
		locationEnabled(true);
		when(offlineGeoDataset.status()).thenThrow(new IllegalStateException("dataset unreadable"));

		GeoDatasetAutoUpdateScheduler scheduler = scheduler(Clock.fixed(Instant.parse("2026-08-01T09:15:00Z"),
				ZoneId.of("UTC")));

		scheduler.runOnce();

		verify(runner, never()).downloadAndImport();
	}
}