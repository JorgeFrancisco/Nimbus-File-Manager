package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.AcquiredBoundaries;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.GeoDatasetIdentity;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.GeoDatasetState;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoDatasetStateRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;

/**
 * An installation is finished when it says so, and not a step earlier.
 *
 * <p>
 * The levels, the dissolved territories and the publication of the downloaded
 * files cannot share a transaction - a territory the source has no data for must
 * not roll back a worldwide import, and files on disk are not transactional at
 * all. So the presence of boundaries never meant the installation completed, and
 * for a while nothing recorded the difference: a run that imported and then died
 * left a database that looked installed and a next run that believed it.
 *
 * <p>
 * The mark of completion is the last durable write of the sequence. What these
 * hold is the order, because the order is the guarantee: anything that throws
 * above that line leaves the dataset unmarked, and unmarked is what the next run
 * rebuilds.
 */
class GeoDatasetInstallationTest {

	private final WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
	private final BoundarySource boundarySource = mock(BoundarySource.class);
	private final GeoJsonBoundaryImporter importer = mock(GeoJsonBoundaryImporter.class);
	private final BoundaryTerritoryCompletion territoryCompletion = mock(BoundaryTerritoryCompletion.class);
	private final BoundaryGeometryCache geometryCache = mock(BoundaryGeometryCache.class);
	private final GeoDatasetStateRepository geoDatasetStateRepository = mock(GeoDatasetStateRepository.class);

	private GeoDatasetInstallation installation;

	@TempDir
	Path geodata;

	@BeforeEach
	void setUp() {
		when(workspaceManager.geodata()).thenReturn(geodata);
		when(boundarySource.sourceTag()).thenReturn("geoBoundaries");
		when(boundarySource.version()).thenReturn("2026-08-16");
		when(boundarySource.providerLabel()).thenReturn("geoBoundaries CGAZ");
		when(boundarySource.license()).thenReturn("CC BY 4.0");

		installation = new GeoDatasetInstallation(workspaceManager, boundarySource, importer, territoryCompletion,
				geometryCache, geoDatasetStateRepository);
	}

	@Test
	void marksTheInstallationCompleteOnlyAfterEveryOtherStepSucceeded() {
		installation.install(acquired());

		InOrder order = inOrder(importer, territoryCompletion, boundarySource, geometryCache,
				geoDatasetStateRepository);

		order.verify(importer).importDataset(any(), any());
		order.verify(territoryCompletion).complete(any());
		order.verify(boundarySource).commit(any());
		order.verify(geometryCache).invalidate();
		order.verify(geoDatasetStateRepository).markComplete(GeoDatasetState.SINGLETON_ID);
	}

	/**
	 * The import writes the identity of what it installed, so the row and the
	 * boundaries are one fact - which is why the source is asked for all of it here
	 * rather than each part being looked up wherever it happens to be needed.
	 */
	@Test
	void handsTheImportWhoProducedTheDatasetAndWhichVersionItIs() {
		installation.install(acquired());

		ArgumentCaptor<GeoDatasetIdentity> identity = ArgumentCaptor.forClass(GeoDatasetIdentity.class);

		verify(importer).importDataset(any(), identity.capture());

		Assertions.assertThat(identity.getValue().source()).isEqualTo("geoBoundaries");
		Assertions.assertThat(identity.getValue().version()).isEqualTo("2026-08-16");
		Assertions.assertThat(identity.getValue().provider()).isEqualTo("geoBoundaries CGAZ");
		Assertions.assertThat(identity.getValue().license()).isEqualTo("CC BY 4.0");
	}

	/**
	 * A territory pass that fails takes the completion mark with it. The boundaries
	 * it did import stay - rolling back a worldwide import over one missing
	 * territory would be worse - and they stay unmarked, which is what sends the
	 * next run through the rebuild instead of the short path.
	 */
	@Test
	void leavesTheDatasetUnmarkedWhenTheTerritoryStageFails() {
		when(territoryCompletion.complete(any())).thenThrow(new IllegalStateException("territory source down"));

		Assertions.assertThatIllegalStateException().isThrownBy(() -> installation.install(acquired()));

		verify(geoDatasetStateRepository, never()).markComplete(GeoDatasetState.SINGLETON_ID);
		verify(boundarySource, never()).commit(any());
	}

	/**
	 * And so does a failure to publish the files - the case that used to leave new
	 * ETags on disk over a dataset nobody had finished installing.
	 */
	@Test
	void leavesTheDatasetUnmarkedWhenPublishingTheFilesFails() {
		doThrow(new IllegalStateException("could not publish")).when(boundarySource).commit(any());

		Assertions.assertThatIllegalStateException().isThrownBy(() -> installation.install(acquired()));

		verify(geoDatasetStateRepository, never()).markComplete(GeoDatasetState.SINGLETON_ID);
	}

	@Test
	void countsWhatTheTerritoryStageAddedOnTopOfTheMainImport() {
		when(importer.importDataset(any(), any())).thenReturn(52697L);
		when(territoryCompletion.complete(any())).thenReturn(88L);

		Assertions.assertThat(installation.install(acquired())).isEqualTo(52785L);
	}

	private AcquiredBoundaries acquired() {
		return new AcquiredBoundaries(List.of(), true);
	}
}