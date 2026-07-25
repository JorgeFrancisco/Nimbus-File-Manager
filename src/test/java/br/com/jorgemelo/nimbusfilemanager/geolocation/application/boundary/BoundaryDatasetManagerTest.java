package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.BoundaryMetadata;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LeveledBoundaryFile;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.OfflineGeoDatasetStatus;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoAdminBoundaryRepository;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;

/**
 * Lifecycle orchestration of the boundary dataset: status reporting, the
 * download/import happy path (including territory gap-filling), error recording
 * and removal.
 */
class BoundaryDatasetManagerTest {

	private final WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
	private final BoundarySource boundarySource = mock(BoundarySource.class);
	private final GeoJsonBoundaryImporter importer = mock(GeoJsonBoundaryImporter.class);
	private final BoundaryMetadataStore metadataStore = mock(BoundaryMetadataStore.class);
	private final GeoAdminBoundaryRepository repository = mock(GeoAdminBoundaryRepository.class);
	private final BoundaryGeometryCache geometryCache = mock(BoundaryGeometryCache.class);
	private final AppSettingService appSettingService = mock(AppSettingService.class);

	private BoundaryDatasetManager manager;

	@TempDir
	Path geodata;

	@BeforeEach
	void setUp() {
		when(workspaceManager.geodata()).thenReturn(geodata);
		when(boundarySource.sourceTag()).thenReturn("geoBoundaries");
		when(boundarySource.version()).thenReturn("v1");
		when(boundarySource.providerLabel()).thenReturn("geoBoundaries");
		when(boundarySource.license()).thenReturn("ODbL");

		manager = new BoundaryDatasetManager(workspaceManager, boundarySource, importer, metadataStore, repository,
				geometryCache, appSettingService, Clock.systemDefaultZone());
	}

	@Test
	void statusIsUnavailableWhenNoMetadataExists() {
		when(metadataStore.read()).thenReturn(Optional.empty());

		OfflineGeoDatasetStatus status = manager.status();

		Assertions.assertThat(status.available()).isFalse();
		Assertions.assertThat(status.directory()).isNotBlank();
	}

	@Test
	void statusIsUnavailableWhenMetadataExistsButNothingImported() {
		when(metadataStore.read()).thenReturn(Optional.of(BoundaryMetadata.builder().lastError("half done").build()));
		when(repository.count()).thenReturn(0L);

		OfflineGeoDatasetStatus status = manager.status();

		Assertions.assertThat(status.available()).isFalse();
		Assertions.assertThat(status.lastError()).isEqualTo("half done");
	}

	@Test
	void statusIsAvailableWhenDatasetIsImported() {
		LocalDateTime now = LocalDateTime.parse("2026-07-12T10:00:00");
		when(metadataStore.read()).thenReturn(Optional.of(BoundaryMetadata.builder().provider("geoBoundaries")
				.license("ODbL").version("v1").importedRecords(10).sizeBytes(2048).importedAt(now).build()));
		when(repository.count()).thenReturn(10L);

		OfflineGeoDatasetStatus status = manager.status();

		Assertions.assertThat(status.available()).isTrue();
		Assertions.assertThat(status.version()).isEqualTo("v1");
		Assertions.assertThat(status.importedRecords()).isEqualTo(10);
		Assertions.assertThat(status.provider()).isEqualTo("geoBoundaries");
	}

	@Test
	void downloadAndImportWritesMetadataAndSkipsTerritoriesWhenDisabled() {
		when(boundarySource.fetch(any())).thenReturn(List.of());
		when(importer.importDataset(any(), eq("geoBoundaries"), eq("v1"))).thenReturn(100L);
		when(appSettingService.booleanValue(eq(SettingsConstants.BOUNDARY_AUTO_TERRITORIES), anyBoolean()))
				.thenReturn(false);
		when(metadataStore.read()).thenReturn(Optional.of(
				BoundaryMetadata.builder().importedRecords(100).importedAt(LocalDateTime.now()).version("v1").build()));
		when(repository.count()).thenReturn(100L);

		OfflineGeoDatasetStatus status = manager.downloadAndImport();

		Assertions.assertThat(status.available()).isTrue();

		BoundaryMetadata written = captureWrittenMetadata();
		Assertions.assertThat(written.getImportedRecords()).isEqualTo(100);
		Assertions.assertThat(written.getProvider()).isEqualTo("geoBoundaries");
		Assertions.assertThat(written.getVersion()).isEqualTo("v1");
		Assertions.assertThat(written.getDownloadedAt()).isNotNull();
		Assertions.assertThat(written.getImportedAt()).isNotNull();
		verify(geometryCache).invalidate();
	}

	@Test
	void downloadAndImportAddsSupplementalTerritories() {
		when(boundarySource.fetch(any())).thenReturn(List.of());
		when(importer.importDataset(any(), any(), any())).thenReturn(100L);
		when(appSettingService.booleanValue(eq(SettingsConstants.BOUNDARY_AUTO_TERRITORIES), anyBoolean()))
				.thenReturn(true);
		when(repository.findDistinctCountryCodes(AdminBoundaryKind.COUNTRY)).thenReturn(List.of());
		when(boundarySource.fetchMissingCountries(any(), any()))
				.thenReturn(List.of(new LeveledBoundaryFile(AdminBoundaryKind.COUNTRY, geodata)));
		when(importer.importExtra(any(), any(), any())).thenReturn(5L);
		when(metadataStore.read()).thenReturn(
				Optional.of(BoundaryMetadata.builder().importedRecords(105).importedAt(LocalDateTime.now()).build()));
		when(repository.count()).thenReturn(105L);

		manager.downloadAndImport();

		Assertions.assertThat(captureWrittenMetadata().getImportedRecords()).isEqualTo(105);
	}

	@Test
	void downloadAndImportRecordsLastErrorAndRethrowsWhenNoPreviousMetadata() {
		when(boundarySource.fetch(any())).thenThrow(new IllegalStateException("boom"));
		when(metadataStore.read()).thenReturn(Optional.empty());

		Assertions.assertThatThrownBy(() -> manager.downloadAndImport()).isInstanceOf(IllegalStateException.class)
				.hasMessage("boom");

		Assertions.assertThat(captureWrittenMetadata().getLastError()).isEqualTo("boom");
	}

	@Test
	void downloadAndImportUpdatesLastErrorOnExistingMetadata() {
		BoundaryMetadata existing = BoundaryMetadata.builder().provider("geoBoundaries").importedRecords(50).build();

		when(boundarySource.fetch(any())).thenThrow(new IllegalStateException("network down"));
		when(metadataStore.read()).thenReturn(Optional.of(existing));

		Assertions.assertThatThrownBy(() -> manager.downloadAndImport()).hasMessage("network down");

		Assertions.assertThat(existing.getLastError()).isEqualTo("network down");
		verify(metadataStore).write(existing);
	}

	@Test
	void removeDeletesRowsDownloadsFolderAndMetadata() throws IOException {
		Path downloads = geodata.resolve("downloads");

		Files.createDirectories(downloads);
		Files.writeString(downloads.resolve("adm0.geojson"), "{}");

		manager.remove();

		verify(repository).deleteAllRows();
		verify(geometryCache).invalidate();
		verify(metadataStore).delete();

		Assertions.assertThat(downloads).doesNotExist();
	}

	private BoundaryMetadata captureWrittenMetadata() {
		ArgumentCaptor<BoundaryMetadata> captor = ArgumentCaptor.forClass(BoundaryMetadata.class);

		verify(metadataStore).write(captor.capture());

		return captor.getValue();
	}
}