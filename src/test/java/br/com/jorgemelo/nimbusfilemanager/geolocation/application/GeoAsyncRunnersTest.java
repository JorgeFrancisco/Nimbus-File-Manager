package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LocationRebuildResult;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.OfflineGeoDatasetStatus;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationRebuildScope;

class GeoAsyncRunnersTest {

	@Test
	void datasetRunnerShouldExposeCompletionResult() {
		OfflineGeoDataset dataset = mock(OfflineGeoDataset.class);

		MediaLocationService locations = mock(MediaLocationService.class);

		OfflineGeoDatasetStatus result = new OfflineGeoDatasetStatus(true, "2026-07-12", 234_866, 1024,
				LocalDateTime.now(), LocalDateTime.now(), "geodata", null, "geoBoundaries CGAZ", "CC BY 4.0");

		when(dataset.downloadAndImport()).thenReturn(result);

		GeoDatasetAsyncRunner runner = new GeoDatasetAsyncRunner(dataset, locations, new GeoDatasetProgress(),
				new BackgroundWorkGate());

		Assertions.assertThat(runner.start()).isTrue();

		runner.downloadAndImport();

		Assertions.assertThat(runner.isRunning()).isFalse();
		Assertions.assertThat(runner.lastResult()).isSameAs(result);
		Assertions.assertThat(runner.lastError()).isNull();
		Assertions.assertThat(runner.progress()).isNotNull();
	}

	@Test
	void rebuildRunnerShouldClearOldResultWhenStartingAndExposeNewSummary() {
		LocationRebuildService service = mock(LocationRebuildService.class);

		var first = new LocationRebuildResult(LocationRebuildScope.ALL, 3, 2, 1, 0);
		var second = new LocationRebuildResult(LocationRebuildScope.PENDING, 5, 4, 1, 0);

		LocationRebuildAsyncRunner runner = new LocationRebuildAsyncRunner(service);

		when(service.rebuild(ArgumentMatchers.eq(LocationRebuildScope.ALL), ArgumentMatchers.any())).thenReturn(first);
		when(service.rebuild(ArgumentMatchers.eq(LocationRebuildScope.PENDING), ArgumentMatchers.any()))
				.thenReturn(second);

		runner.start(LocationRebuildScope.ALL);
		runner.rebuild(LocationRebuildScope.ALL);

		Assertions.assertThat(runner.lastResult()).isSameAs(first);

		runner.start(LocationRebuildScope.PENDING);

		Assertions.assertThat(runner.lastResult()).isNull();

		runner.rebuild(LocationRebuildScope.PENDING);

		Assertions.assertThat(runner.lastResult()).isSameAs(second);
	}
}