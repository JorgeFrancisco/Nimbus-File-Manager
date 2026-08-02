package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.OfflineGeoDatasetStatus;

/**
 * One download at a time, and what the admin screen is told about it.
 *
 * <p>
 * The request that starts the import is long gone by the time it ends - it runs
 * on the geolocation executor - so whatever the screen later shows has to be
 * kept here. A failure that is not kept reads as a finished import.
 */
class GeoDatasetAsyncRunnerTest {

	private final OfflineGeoDataset dataset = mock(OfflineGeoDataset.class);
	private final MediaLocationService mediaLocationService = mock(MediaLocationService.class);

	private final GeoDatasetAsyncRunner runner = new GeoDatasetAsyncRunner(dataset, mediaLocationService,
			new GeoDatasetProgress(), new BackgroundWorkGate());

	/**
	 * Two concurrent imports would write the same boundary table, ending with
	 * neither dataset whole, so the second request is refused rather than queued.
	 */
	@Test
	void refusesToStartASecondImportWhileOneIsRunning() {
		Assertions.assertThat(runner.start()).isTrue();
		Assertions.assertThat(runner.start()).isFalse();
		Assertions.assertThat(runner.isRunning()).isTrue();
	}

	/**
	 * A new dataset version invalidates every resolution made against the old one,
	 * so the cache goes with it.
	 */
	@Test
	void reportsTheImportItFinishedAndDropsTheStaleLocationCache() {
		OfflineGeoDatasetStatus status = new OfflineGeoDatasetStatus(true, "v2", 1200, 4096, null, null, "geodata",
				null, "TEST", "ODbL");

		when(dataset.downloadAndImport()).thenReturn(status);

		runner.start();
		runner.downloadAndImport();

		Assertions.assertThat(runner.lastResult()).isSameAs(status);
		Assertions.assertThat(runner.lastError()).isNull();
		Assertions.assertThat(runner.isRunning()).isFalse();

		verify(mediaLocationService).clearCache();
	}

	@Test
	void keepsTheReasonAnImportFailed() {
		when(dataset.downloadAndImport()).thenThrow(new IllegalStateException("the provider is unreachable"));

		runner.start();
		runner.downloadAndImport();

		Assertions.assertThat(runner.lastError()).isEqualTo("the provider is unreachable");
		Assertions.assertThat(runner.lastResult()).isNull();
		Assertions.assertThat(runner.isRunning()).isFalse();

		verify(mediaLocationService, never()).clearCache();
	}

	/** A new run starts clean, or the last failure would haunt the next screen. */
	@Test
	void clearsWhatThePreviousRunLeftBehind() {
		when(dataset.downloadAndImport()).thenThrow(new IllegalStateException("the provider is unreachable"));

		runner.start();
		runner.downloadAndImport();

		Assertions.assertThat(runner.start()).isTrue();
		Assertions.assertThat(runner.lastError()).isNull();
		Assertions.assertThat(runner.lastResult()).isNull();
	}
}