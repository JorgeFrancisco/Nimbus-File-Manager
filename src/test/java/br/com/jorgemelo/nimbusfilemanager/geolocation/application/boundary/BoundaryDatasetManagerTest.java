package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.GeoDatasetProgress;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.AcquiredBoundaries;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;

/**
 * When an update does the work, and when it has nothing to do.
 *
 * <p>
 * The dataset used to be rebuilt on every pass whether or not anything had
 * changed: the source knew the server had answered "not modified", the manager
 * received a list of paths, and the two facts never met. That cost a full delete
 * and reinsert of every boundary in the library, the write-ahead log that comes
 * with it, and checkpoints heavy enough to stall unrelated work for a minute at
 * a time.
 *
 * <p>
 * These hold both halves of the condition that replaced it. Unchanged bytes are
 * not enough on their own - a reset database, a removed dataset and a run that
 * died mid-installation all leave files whose ETags still match - so every test
 * that expects the short path also has an installation behind it, and every test
 * that takes one of those away expects the work to happen.
 */
class BoundaryDatasetManagerTest {

	private final WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
	private final BoundarySource boundarySource = mock(BoundarySource.class);
	private final InstalledGeoDataset installedGeoDataset = mock(InstalledGeoDataset.class);
	private final GeoDatasetInstallation installation = mock(GeoDatasetInstallation.class);
	private final GeoDatasetRemoval removal = mock(GeoDatasetRemoval.class);
	private final GeoDatasetProgress progress = mock(GeoDatasetProgress.class);

	private BoundaryDatasetManager manager;

	@TempDir
	Path geodata;

	@BeforeEach
	void setUp() {
		when(workspaceManager.geodata()).thenReturn(geodata);

		manager = new BoundaryDatasetManager(workspaceManager, boundarySource, installedGeoDataset, installation,
				removal, progress);
	}

	/**
	 * The case the whole change exists for: nothing moved at the source and what is
	 * installed is usable, so not one boundary is touched.
	 */
	@Test
	void importsNothingWhenEveryLevelIsUnchangedAndTheInstalledDatasetIsUsable() {
		unchanged();

		when(installedGeoDataset.isUsable()).thenReturn(true);

		Assertions.assertThat(manager.bringUpToDate()).as("nothing was installed").isFalse();

		verifyNoInteractions(installation);
	}

	/**
	 * A migration that reset the dataset, or a removal, leaves the downloaded files
	 * and their ETags exactly as they were. The server says unchanged and it is
	 * telling the truth - about bytes that have nothing behind them any more.
	 */
	@Test
	void rebuildsWhenNothingChangedButThereIsNoUsableDatasetInstalled() {
		unchanged();

		when(installedGeoDataset.isUsable()).thenReturn(false);

		Assertions.assertThat(manager.bringUpToDate()).isTrue();

		verify(installation).install(any());
	}

	/** And when the source did bring something new, it is installed. */
	@Test
	void installsWhenTheSourceReportsAChange() {
		when(boundarySource.fetch(any())).thenReturn(new AcquiredBoundaries(List.of(), true));

		Assertions.assertThat(manager.bringUpToDate()).isTrue();

		verify(installation).install(any());
	}

	/**
	 * A changed source is installed even over a dataset that is perfectly usable -
	 * usable is not the same as current, and this is the direction that would
	 * silently freeze the library on an old version if it were confused.
	 */
	@Test
	void neverSkipsAChangeBecauseSomethingUsableIsAlreadyInstalled() {
		when(boundarySource.fetch(any())).thenReturn(new AcquiredBoundaries(List.of(), true));
		when(installedGeoDataset.isUsable()).thenReturn(true);

		manager.bringUpToDate();

		verify(installation).install(any());
	}

	/**
	 * The short path publishes nothing and undoes nothing: there was never anything
	 * staged, and the files on disk are the ones the installation was built from.
	 */
	@Test
	void neitherPublishesNorDiscardsWhenThereWasNothingToInstall() {
		unchanged();

		when(installedGeoDataset.isUsable()).thenReturn(true);

		manager.bringUpToDate();

		verify(boundarySource, never()).commit(any());
		verify(boundarySource, never()).discard(any());
	}

	/**
	 * A failed acquisition drops what it staged, which is what leaves the previous
	 * dataset working - and it must not be mistaken for a run that had nothing to
	 * do.
	 */
	@Test
	void discardsWhatItStagedWhenTheAcquisitionFails() {
		when(boundarySource.fetch(any())).thenThrow(new IllegalStateException("network down"));

		Assertions.assertThatIllegalStateException().isThrownBy(() -> manager.bringUpToDate())
				.withMessage("network down");

		verify(boundarySource).discard(any());
		verify(boundarySource, never()).commit(any());
		verifyNoInteractions(installation);
	}

	/** The same for a failure that happens once the installation is under way. */
	@Test
	void discardsWhatItStagedWhenTheInstallationFails() {
		when(boundarySource.fetch(any())).thenReturn(new AcquiredBoundaries(List.of(), true));
		when(installation.install(any())).thenThrow(new IllegalStateException("broken geojson"));

		Assertions.assertThatIllegalStateException().isThrownBy(() -> manager.bringUpToDate());

		verify(boundarySource).discard(any());
	}

	@Test
	void removalIsDelegatedWhole() {
		manager.remove();

		verify(removal).remove();
	}

	private void unchanged() {
		when(boundarySource.fetch(any())).thenReturn(new AcquiredBoundaries(List.of(), false));
	}
}