package br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.GeoDatasetProgress;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary.BoundaryDatasetManager;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary.BoundaryGeometryCache;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary.BoundarySource;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary.BoundaryTerritoryCompletion;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary.GeoDatasetInstallation;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary.GeoDatasetRemoval;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary.GeoJsonBoundaryImporter;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary.InstalledGeoDataset;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.AcquiredBoundaries;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.GeoDatasetIdentity;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LeveledBoundaryFile;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.GeoDatasetState;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;

/**
 * The installed dataset and the record of what it is, against the database that
 * has to keep them together.
 *
 * <p>
 * These facts used to live in a JSON file written after the import committed,
 * and the gap between the two media was not theoretical: a run that imported the
 * boundaries and then failed left a database holding one dataset and a file
 * describing the one before it, with nothing anywhere able to say when the
 * installed rows had arrived. Reconstructing that afterwards turned out to be
 * impossible rather than laborious, which is why the fact moved into the
 * transaction that writes the rows.
 *
 * <p>
 * What is proved here is that it really is one write. The import is asked to run
 * for real, against real PostgreSQL, and both halves are read back.
 */
class GeoDatasetStateIntegrationTest extends SharedPostgresIntegrationTest {

	private static final String POLYGON = "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[0,1],[1,1],[1,0],[0,0]]]}";

	private static final GeoDatasetIdentity IDENTITY = new GeoDatasetIdentity("geoBoundaries", "2026-08-16",
			"geoBoundaries CGAZ", "CC BY 4.0");

	@Autowired
	private GeoJsonBoundaryImporter importer;

	@Autowired
	private GeoDatasetStateRepository geoDatasetStateRepository;

	@Autowired
	private GeoAdminBoundaryRepository geoAdminBoundaryRepository;

	@Autowired
	private InstalledGeoDataset installedGeoDataset;

	@Autowired
	private BoundaryGeometryCache geometryCache;

	@Autowired
	private GeoDatasetRemoval removal;

	@Autowired
	private GeoDatasetProgress progress;

	@Autowired
	private WorkspaceManager workspaceManager;

	@TempDir
	Path dir;

	/**
	 * A fresh database arrives with boundaries and with nothing claiming any are
	 * installed - which is what sends the first run through the import instead of
	 * letting it believe the files on disk are enough.
	 */
	@Test
	void startsWithNoInstallationOnRecord() {
		Assertions.assertThat(geoDatasetStateRepository.findInstalled()).isEmpty();
		Assertions.assertThat(geoAdminBoundaryRepository.count()).isZero();
	}

	/**
	 * The import writes both halves, and writes the installation as unfinished:
	 * the territories and the publication still have to happen, and until they do
	 * nothing may treat this as a dataset to skip work over.
	 */
	@Test
	void theImportWritesTheIdentityOfWhatItInstalledAndLeavesItIncomplete() throws IOException {
		long imported = importer.importDataset(List.of(new LeveledBoundaryFile(AdminBoundaryKind.COUNTRY, geoJson())),
				IDENTITY);

		Assertions.assertThat(imported).isEqualTo(1);
		Assertions.assertThat(geoAdminBoundaryRepository.count()).isEqualTo(1);

		Optional<GeoDatasetState> state = geoDatasetStateRepository.findInstalled();

		Assertions.assertThat(state).isPresent();
		Assertions.assertThat(state.get().getDatasetVersion()).isEqualTo("2026-08-16");
		Assertions.assertThat(state.get().getSource()).isEqualTo("geoBoundaries");
		Assertions.assertThat(state.get().getProvider()).isEqualTo("geoBoundaries CGAZ");
		Assertions.assertThat(state.get().getLicense()).isEqualTo("CC BY 4.0");
		Assertions.assertThat(state.get().isComplete()).as("the installation has not finished yet").isFalse();

		Assertions.assertThat(state.get().getImportedAt())
				.as("stamped by the transaction that wrote the boundaries").isNotNull();
	}

	/** And the mark of completion is a write of its own, made later. */
	@Test
	void markingCompleteTurnsTheInstallationIntoOneThatMayBeSkipped() throws IOException {
		importer.importDataset(List.of(new LeveledBoundaryFile(AdminBoundaryKind.COUNTRY, geoJson())), IDENTITY);

		Assertions.assertThat(geoDatasetStateRepository.markComplete(GeoDatasetState.SINGLETON_ID)).isEqualTo(1);

		// Read back with no flush of its own: the mark is a statement that bypasses
		// the persistence context, and a caller still holding the entity it loaded is
		// exactly who would otherwise be handed the answer from before it.
		Assertions.assertThat(geoDatasetStateRepository.findInstalled()).get()
				.extracting(GeoDatasetState::isComplete).isEqualTo(true);
	}

	/**
	 * The mark runs where production runs it: with no transaction anywhere on the
	 * thread.
	 *
	 * <p>
	 * Every other test here inherits one from the base class, and that inherited
	 * transaction is exactly what hid the defect: a modifying query has none of its
	 * own, so the last write of a successful installation threw
	 * {@code TransactionRequiredException} in production while passing here. The
	 * suspension below is what makes the two agree.
	 *
	 * <p>
	 * It marks an installation that does not exist, so nothing is committed and the
	 * shared container keeps the isolation its base class promises - and it still
	 * proves the point, because the flush that used to fail happens before the
	 * update looks for a row.
	 */
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void marksWhenNothingOnTheThreadOpenedATransaction() {
		Assertions.assertThat(geoDatasetStateRepository.markComplete(GeoDatasetState.SINGLETON_ID)).isZero();
	}

	/**
	 * Marking an installation nobody recorded changes nothing and says so, rather
	 * than creating a row that would claim a dataset exists.
	 */
	@Test
	void marksNothingWhenThereIsNoInstallationOnRecord() {
		Assertions.assertThat(geoDatasetStateRepository.markComplete(GeoDatasetState.SINGLETON_ID)).isZero();
	}

	/**
	 * A second import replaces the record rather than adding one: the table holds
	 * the installation, and there is only ever one of those.
	 */
	@Test
	void aSecondImportReplacesTheRecordOfTheFirst() throws IOException {
		importer.importDataset(List.of(new LeveledBoundaryFile(AdminBoundaryKind.COUNTRY, geoJson())), IDENTITY);

		importer.importDataset(List.of(new LeveledBoundaryFile(AdminBoundaryKind.COUNTRY, geoJson())),
				new GeoDatasetIdentity("geoBoundaries", "2026-09-01", "geoBoundaries CGAZ", "CC BY 4.0"));

		Assertions.assertThat(geoDatasetStateRepository.count()).isEqualTo(1);
		Assertions.assertThat(geoDatasetStateRepository.findInstalled()).get()
				.extracting(GeoDatasetState::getDatasetVersion).isEqualTo("2026-09-01");
	}

	/**
	 * The defect this whole change exists to remove, proved against the database
	 * rather than against a mock.
	 *
	 * <p>
	 * A complete installation is on record, the source reports every level
	 * unchanged, and the update runs. What used to happen here was a delete of every
	 * boundary followed by a reinsert of every boundary - roughly a gigabyte of
	 * GeoJSON parsed to arrive at the rows already present, and the write-ahead log
	 * paying for all of it. The rows and the moment they were imported are read
	 * before and after: identical means nothing was rewritten.
	 */
	@Test
	void anUnchangedSourceOverACompleteInstallationRewritesNothing() throws IOException {
		importer.importDataset(List.of(new LeveledBoundaryFile(AdminBoundaryKind.COUNTRY, geoJson())), IDENTITY);
		geoDatasetStateRepository.markComplete(GeoDatasetState.SINGLETON_ID);

		long boundariesBefore = geoAdminBoundaryRepository.count();
		GeoDatasetState before = geoDatasetStateRepository.findInstalled().orElseThrow();

		Assertions.assertThat(managerOver(unchangedSource(List.of())).bringUpToDate()).as("nothing was installed")
				.isFalse();

		Assertions.assertThat(geoAdminBoundaryRepository.count()).as("not one boundary was rewritten")
				.isEqualTo(boundariesBefore);

		GeoDatasetState after = geoDatasetStateRepository.findInstalled().orElseThrow();

		Assertions.assertThat(after.getImportedAt()).as("the installation was not replaced")
				.isEqualTo(before.getImportedAt());
		Assertions.assertThat(after.getDatasetVersion()).isEqualTo(before.getDatasetVersion());
	}

	/**
	 * And the same unchanged source over an installation that never finished does
	 * the work, from the files already on disk. This is the state a reset migration
	 * leaves behind, and the reason unchanged bytes alone may never authorise a
	 * skip.
	 */
	@Test
	void anUnchangedSourceOverAnUnfinishedInstallationRebuilds() throws IOException {
		importer.importDataset(List.of(new LeveledBoundaryFile(AdminBoundaryKind.COUNTRY, geoJson())), IDENTITY);

		Assertions.assertThat(installedGeoDataset.isUsable()).as("never marked complete").isFalse();

		// The files a reset leaves on disk: unchanged at the source, and all a rebuild
		// needs - not one byte is downloaded again.
		BoundarySource source = unchangedSource(List.of(new LeveledBoundaryFile(AdminBoundaryKind.COUNTRY, geoJson())));

		Assertions.assertThat(managerOver(source).bringUpToDate()).as("it rebuilt rather than skipped").isTrue();

		Assertions.assertThat(installedGeoDataset.isUsable()).as("and finished the installation this time").isTrue();
	}

	private Path geoJson() throws IOException {
		Path file = dir.resolve("adm0.geojson");

		Files.writeString(file, "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":"
				+ "{\"shapeName\":\"Brasil\",\"shapeGroup\":\"BRA\"},\"geometry\":" + POLYGON + "}]}");

		return file;
	}

	/**
	 * The real importer and the real state repository, over the database this test
	 * runs against - and a territory stage that is stubbed out, because completing
	 * territories reaches the network and this is not what is being proved here.
	 */
	private BoundaryDatasetManager managerOver(BoundarySource source) {
		GeoDatasetInstallation offlineInstallation = new GeoDatasetInstallation(workspaceManager, source, importer,
				mock(BoundaryTerritoryCompletion.class), geometryCache, geoDatasetStateRepository);

		return new BoundaryDatasetManager(workspaceManager, source, installedGeoDataset, offlineInstallation, removal,
				progress);
	}

	/** A source that consulted the server and found every level identical. */
	private BoundarySource unchangedSource(List<LeveledBoundaryFile> files) {
		BoundarySource source = mock(BoundarySource.class);

		when(source.fetch(any())).thenReturn(new AcquiredBoundaries(files, false));
		when(source.sourceTag()).thenReturn("geoBoundaries");
		when(source.version()).thenReturn("2026-08-16");
		when(source.providerLabel()).thenReturn("geoBoundaries CGAZ");
		when(source.license()).thenReturn("CC BY 4.0");

		return source;
	}
}