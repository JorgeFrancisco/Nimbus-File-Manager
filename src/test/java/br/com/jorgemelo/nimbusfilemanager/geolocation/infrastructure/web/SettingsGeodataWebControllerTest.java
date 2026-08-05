package br.com.jorgemelo.nimbusfilemanager.geolocation.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionStatusNames;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.GeoLauncher;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.GeoRunReader;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.MediaLocationService;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.OfflineGeoDataset;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants.GeolocationConstants;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationRebuildScope;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Geographic-dataset admin actions: guards while an inventory, an import or a
 * rebuild is running, background start success and cache clearing.
 */
class SettingsGeodataWebControllerTest {

	private final UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
	private final OfflineGeoDataset offlineGeoDataset = mock(OfflineGeoDataset.class);
	private final MediaLocationService mediaLocationService = mock(MediaLocationService.class);
	private final GeoLauncher geoLauncher = mock(GeoLauncher.class);
	private final GeoRunReader geoRunReader = mock(GeoRunReader.class);
	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final InventoryRunningState inventoryRunningState = new InventoryRunningState(executionRepository);

	private final AppSettingService appSettingService = mock(AppSettingService.class);

	private final SettingsGeodataWebController controller = new SettingsGeodataWebController(preferences,
			offlineGeoDataset, mediaLocationService, geoLauncher, geoRunReader, inventoryRunningState,
			appSettingService);

	private final TestingAuthenticationToken auth = new TestingAuthenticationToken("admin@x", "pw");

	@Test
	void rebuildIsQueuedWithTheScopeThatWasPicked() {
		queued();

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.rebuildLocations(LocationRebuildScope.PENDING, auth, redirect);

		verify(geoLauncher).rebuildLocations(LocationRebuildScope.PENDING);
		verify(preferences).save("admin@x", GeolocationConstants.GEO_PAGE_KEY,
				GeolocationConstants.GEO_REBUILD_SCOPE_KEY, "PENDING");

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("success");
	}

	@Test
	void downloadIsQueued() {
		queued();

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.downloadGeoDataset(redirect);

		verify(geoLauncher).updateDataset();

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("success");
	}

	@Test
	void removeRejectedWhileImportRunning() {
		when(geoRunReader.busy()).thenReturn(true);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.removeGeoDataset(redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("error");

		verify(offlineGeoDataset, never()).remove();
	}

	@Test
	void removeDeletesDatasetWhenIdle() {
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
	void clearCacheBlockedWhileInventoryRunning() {
		when(executionRepository.existsByExecutionTypeAndStatusIn(ExecutionType.INVENTORY,
				ExecutionStatusNames.ACTIVE)).thenReturn(true);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.clearGeoCache(redirect);

		Assertions.assertThat(redirect.getFlashAttributes().get("error").toString()).contains("inventário");

		verify(mediaLocationService, never()).clearCache();
	}

	@Test
	void geoActionsBlockedWhileInventoryRunning() {
		when(executionRepository.existsByExecutionTypeAndStatusIn(ExecutionType.INVENTORY,
				ExecutionStatusNames.ACTIVE)).thenReturn(true);

		RedirectAttributesModelMap download = new RedirectAttributesModelMap();

		controller.downloadGeoDataset(download);

		Assertions.assertThat(download.getFlashAttributes().get("error").toString()).contains("inventário");

		verify(geoLauncher, never()).updateDataset();

		RedirectAttributesModelMap rebuild = new RedirectAttributesModelMap();

		controller.rebuildLocations(LocationRebuildScope.PENDING, auth, rebuild);

		Assertions.assertThat(rebuild.getFlashAttributes()).containsKey("error");

		verify(geoLauncher, never()).rebuildLocations(any());

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
		when(geoRunReader.busy()).thenReturn(true);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.clearGeoCache(redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("error");

		verify(mediaLocationService, never()).clearCache();
	}

	/**
	 * Turning it on records the choice and stops there: the dataset is acquired in
	 * the background by the auto update, which is what keeps a 2 GB download out of
	 * the operator's way.
	 */
	@Test
	void enablingRecordsTheSettingAndLeavesTheDownloadToTheBackground() {
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.enableLocation(auth, redirect);

		verify(appSettingService).update(eq(SettingsConstants.LOCATION_ENABLED), eq("true"), any());
		verify(geoLauncher, never()).updateDataset();

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("success");
	}

	/** Keeping the files is the default answer: nothing is deleted unasked. */
	@Test
	void disablingKeepsTheDownloadedFilesUnlessAskedToRemoveThem() {
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.disableLocation(false, auth, redirect);

		verify(appSettingService).update(eq(SettingsConstants.LOCATION_ENABLED), eq("false"), any());
		verify(offlineGeoDataset, never()).remove();

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("success");
	}

	@Test
	void disablingRemovesTheDataWhenThatIsTheAnswer() {
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.disableLocation(true, auth, redirect);

		verify(appSettingService).update(eq(SettingsConstants.LOCATION_ENABLED), eq("false"), any());
		verify(offlineGeoDataset).remove();

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("success");
	}

	/**
	 * Removing the dataset an import is writing, or a rebuild is reading, would
	 * pull the ground from under it - so the answer is a reason on screen, not a
	 * half-applied disable.
	 */
	@Test
	void disablingBlockedWhileTheDatasetImportRuns() {
		when(geoRunReader.busy()).thenReturn(true);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.disableLocation(true, auth, redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("error");

		verify(appSettingService, never()).update(any(), any(), any());
		verify(offlineGeoDataset, never()).remove();
	}

	private void queued() {
		when(geoLauncher.rebuildLocations(any())).thenReturn(Optional.of(new Execution()));
		when(geoLauncher.updateDataset()).thenReturn(Optional.of(new Execution()));
	}

	/**
	 * The queue refused to take the request - the application is closing - so the
	 * screen says so rather than reporting a rebuild that was never written.
	 */
	@Test
	void aRequestThatCouldNotBeQueuedIsReportedAsAnError() {
		RedirectAttributesModelMap rebuild = new RedirectAttributesModelMap();

		controller.rebuildLocations(LocationRebuildScope.PENDING, auth, rebuild);

		Assertions.assertThat(rebuild.getFlashAttributes()).containsKey("error");

		RedirectAttributesModelMap download = new RedirectAttributesModelMap();

		controller.downloadGeoDataset(download);

		Assertions.assertThat(download.getFlashAttributes()).containsKey("error");
	}
}