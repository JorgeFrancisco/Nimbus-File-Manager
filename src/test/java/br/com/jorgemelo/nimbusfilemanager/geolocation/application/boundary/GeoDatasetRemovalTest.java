package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoAdminBoundaryRepository;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoDatasetStateRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;

/**
 * Uninstalling has to take everything, and the record of the installation is the
 * part it would be easiest to forget.
 *
 * <p>
 * A leftover state row claiming a complete installation, over a table with
 * nothing in it, is exactly the shape an update is entitled to skip its work
 * over - so forgetting it here would turn "remove the dataset" into "stop
 * resolving anything, quietly, until somebody reinstalls by hand".
 */
class GeoDatasetRemovalTest {

	private final WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
	private final GeoAdminBoundaryRepository geoAdminBoundaryRepository = mock(GeoAdminBoundaryRepository.class);
	private final GeoDatasetStateRepository geoDatasetStateRepository = mock(GeoDatasetStateRepository.class);
	private final BoundaryGeometryCache geometryCache = mock(BoundaryGeometryCache.class);

	private GeoDatasetRemoval removal;

	@TempDir
	Path geodata;

	@BeforeEach
	void setUp() {
		when(workspaceManager.geodata()).thenReturn(geodata);

		removal = new GeoDatasetRemoval(workspaceManager, geoAdminBoundaryRepository, geoDatasetStateRepository,
				geometryCache);
	}

	@Test
	void takesTheBoundariesTheRecordOfThemTheCacheAndTheDownloadedFiles() throws IOException {
		Path downloads = geodata.resolve("downloads");

		Files.createDirectories(downloads);
		Files.writeString(downloads.resolve("adm0.geojson"), "{}");

		removal.remove();

		verify(geoAdminBoundaryRepository).deleteAllRows();
		verify(geoDatasetStateRepository).deleteAllInBatch();
		verify(geometryCache).invalidate();

		Assertions.assertThat(downloads).doesNotExist();
	}

	/** Nothing downloaded yet is an ordinary state, not a reason to fail. */
	@Test
	void succeedsWhenThereIsNoDownloadsFolderToClean() {
		removal.remove();

		verify(geoAdminBoundaryRepository).deleteAllRows();
		verify(geoDatasetStateRepository).deleteAllInBatch();
		verify(geometryCache).invalidate();
	}

	/** Nested files and folders go too, deepest first. */
	@Test
	void clearsTheWholeDownloadTreeRatherThanItsTopLevel() throws IOException {
		Path nested = geodata.resolve("downloads").resolve("territories").resolve("BRA");

		Files.createDirectories(nested);
		Files.writeString(nested.resolve("adm2.geojson"), "{}");

		removal.remove();

		Assertions.assertThat(geodata.resolve("downloads")).doesNotExist();
	}
}