package br.com.jorgemelo.nimbusfilemanager.geolocation.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.GeoDatasetAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.LocationRebuildAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.MediaLocationService;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.OfflineGeoDataset;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants.GeolocationConstants;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationRebuildScope;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;

/**
 * Geographic-dataset admin actions: guards while an inventory, an import or a
 * rebuild is running, background start success and cache clearing.
 */
class SettingsGeodataWebControllerTest {

	private final UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
	private final OfflineGeoDataset offlineGeoDataset = mock(OfflineGeoDataset.class);
	private final MediaLocationService mediaLocationService = mock(MediaLocationService.class);
	private final GeoDatasetAsyncRunner geoDatasetAsyncRunner = mock(GeoDatasetAsyncRunner.class);
	private final LocationRebuildAsyncRunner locationRebuildAsyncRunner = mock(LocationRebuildAsyncRunner.class);
	private final ExecutionQueryService executionQueryService = mock(ExecutionQueryService.class);
	private final InventoryRunningState inventoryRunningState = new InventoryRunningState(executionQueryService);

	private final SettingsGeodataWebController controller = new SettingsGeodataWebController(preferences,
			offlineGeoDataset, mediaLocationService, geoDatasetAsyncRunner, locationRebuildAsyncRunner,
			inventoryRunningState);

	private final TestingAuthenticationToken auth = new TestingAuthenticationToken("admin@x", "pw");

	private static ExecutionResponse inventoryExecution() {
		return new ExecutionResponse(1L, "INVENTORY", "PROCESSING_FILES", LocalDateTime.now(), null, "src", null, 1, 1,
				0, 0, 0, 0, null, null, "running", false);
	}

	@Test
	void rebuildRejectedWhileDatasetImportRunning() {
		when(geoDatasetAsyncRunner.isRunning()).thenReturn(true);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.rebuildLocations(LocationRebuildScope.PENDING, auth, redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("error");

		verify(locationRebuildAsyncRunner, never()).rebuild(ArgumentMatchers.any());
		// The scope is remembered even though the rebuild could not start.
		verify(preferences).save("admin@x", GeolocationConstants.GEO_PAGE_KEY,
				GeolocationConstants.GEO_REBUILD_SCOPE_KEY, "PENDING");
	}

	@Test
	void rebuildRejectedWhenAlreadyRebuilding() {
		when(geoDatasetAsyncRunner.isRunning()).thenReturn(false);
		when(locationRebuildAsyncRunner.start(LocationRebuildScope.ALL)).thenReturn(false);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.rebuildLocations(LocationRebuildScope.ALL, auth, redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("error");

		verify(locationRebuildAsyncRunner, never()).rebuild(ArgumentMatchers.any());
		verify(preferences).save("admin@x", GeolocationConstants.GEO_PAGE_KEY,
				GeolocationConstants.GEO_REBUILD_SCOPE_KEY, "ALL");
	}

	@Test
	void rebuildStartsInBackground() {
		when(geoDatasetAsyncRunner.isRunning()).thenReturn(false);
		when(locationRebuildAsyncRunner.start(LocationRebuildScope.PENDING)).thenReturn(true);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.rebuildLocations(LocationRebuildScope.PENDING, auth, redirect);

		verify(locationRebuildAsyncRunner).rebuild(LocationRebuildScope.PENDING);
		verify(preferences).save("admin@x", GeolocationConstants.GEO_PAGE_KEY,
				GeolocationConstants.GEO_REBUILD_SCOPE_KEY, "PENDING");

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("success");
	}

	@Test
	void downloadRejectedWhenAlreadyRunning() {
		when(geoDatasetAsyncRunner.start()).thenReturn(false);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.downloadGeoDataset(redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("error");

		verify(geoDatasetAsyncRunner, never()).downloadAndImport();
	}

	@Test
	void downloadStartsInBackground() {
		when(geoDatasetAsyncRunner.start()).thenReturn(true);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.downloadGeoDataset(redirect);

		verify(geoDatasetAsyncRunner).downloadAndImport();

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("success");
	}

	@Test
	void removeRejectedWhileImportRunning() {
		when(geoDatasetAsyncRunner.isRunning()).thenReturn(true);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.removeGeoDataset(redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("error");

		verify(offlineGeoDataset, never()).remove();
	}

	@Test
	void removeDeletesDatasetWhenIdle() {
		when(geoDatasetAsyncRunner.isRunning()).thenReturn(false);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.removeGeoDataset(redirect);

		verify(offlineGeoDataset).remove();

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("success");
	}

	@Test
	void clearCacheReportsRemovedEntries() {
		when(mediaLocationService.clearCache()).thenReturn(5L);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.clearGeoCache(redirect);

		Assertions.assertThat(redirect.getFlashAttributes().get("success").toString()).contains("5");
	}

	@Test
	void datasetActionsBlockedWhileRebuildRunning() {
		// The whole geo section must wait for a running rebuild, not just the
		// rebuild button: importing, removing or clearing the cache underneath a
		// running resolution would corrupt it.
		when(locationRebuildAsyncRunner.isRunning()).thenReturn(true);

		RedirectAttributesModelMap download = new RedirectAttributesModelMap();
		controller.downloadGeoDataset(download);
		Assertions.assertThat(download.getFlashAttributes()).containsKey("error");
		verify(geoDatasetAsyncRunner, never()).downloadAndImport();

		RedirectAttributesModelMap remove = new RedirectAttributesModelMap();
		controller.removeGeoDataset(remove);
		Assertions.assertThat(remove.getFlashAttributes()).containsKey("error");
		verify(offlineGeoDataset, never()).remove();

		RedirectAttributesModelMap clear = new RedirectAttributesModelMap();
		controller.clearGeoCache(clear);
		Assertions.assertThat(clear.getFlashAttributes()).containsKey("error");
		verify(mediaLocationService, never()).clearCache();
	}

	@Test
	void clearCacheBlockedWhileInventoryRunning() {
		when(executionQueryService.active()).thenReturn(Optional.of(inventoryExecution()));

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.clearGeoCache(redirect);

		Assertions.assertThat(redirect.getFlashAttributes().get("error").toString()).contains("inventário");

		verify(mediaLocationService, never()).clearCache();
	}

	@Test
	void geoActionsBlockedWhileInventoryRunning() {
		when(executionQueryService.active()).thenReturn(Optional.of(inventoryExecution()));

		RedirectAttributesModelMap download = new RedirectAttributesModelMap();

		controller.downloadGeoDataset(download);

		Assertions.assertThat(download.getFlashAttributes().get("error").toString()).contains("inventário");

		verify(geoDatasetAsyncRunner, never()).downloadAndImport();

		RedirectAttributesModelMap rebuild = new RedirectAttributesModelMap();

		controller.rebuildLocations(LocationRebuildScope.PENDING, auth, rebuild);

		Assertions.assertThat(rebuild.getFlashAttributes()).containsKey("error");

		verify(locationRebuildAsyncRunner, never()).rebuild(any());

		RedirectAttributesModelMap remove = new RedirectAttributesModelMap();

		controller.removeGeoDataset(remove);

		Assertions.assertThat(remove.getFlashAttributes()).containsKey("error");

		verify(offlineGeoDataset, never()).remove();
	}

	/**
	 * The import writes the very cache this button empties, so clearing it in the
	 * middle would throw away rows the import just resolved.
	 */
	@Test
	void clearCacheBlockedWhileTheDatasetImportRuns() {
		when(geoDatasetAsyncRunner.isRunning()).thenReturn(true);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.clearGeoCache(redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("error");

		verify(mediaLocationService, never()).clearCache();
	}
}