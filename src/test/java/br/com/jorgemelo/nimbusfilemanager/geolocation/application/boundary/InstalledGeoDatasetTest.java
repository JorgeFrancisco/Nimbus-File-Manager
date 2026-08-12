package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.OfflineGeoDatasetStatus;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.GeoDatasetState;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoAdminBoundaryRepository;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoDatasetStateRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;

/**
 * What "there is a dataset installed" is allowed to mean.
 *
 * <p>
 * This is the answer an update trusts before deciding it has nothing to do, so
 * every way of being wrong about it costs a library its geographic resolution
 * until somebody notices. Three separate things have to agree - a state row
 * exists, it says the installation finished, and the boundaries are actually
 * there - and each of these takes one of them away.
 */
class InstalledGeoDatasetTest {

	private final WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
	private final GeoDatasetStateRepository geoDatasetStateRepository = mock(GeoDatasetStateRepository.class);
	private final GeoAdminBoundaryRepository geoAdminBoundaryRepository = mock(GeoAdminBoundaryRepository.class);

	private InstalledGeoDataset installed;

	@TempDir
	Path geodata;

	@BeforeEach
	void setUp() {
		when(workspaceManager.geodata()).thenReturn(geodata);

		installed = new InstalledGeoDataset(workspaceManager, geoDatasetStateRepository, geoAdminBoundaryRepository);
	}

	@Test
	void isUsableWhenAFinishedInstallationHasBoundariesBehindIt() {
		onRecord(true);

		when(geoAdminBoundaryRepository.count()).thenReturn(52785L);

		Assertions.assertThat(installed.isUsable()).isTrue();
	}

	/** Nothing was ever installed here. */
	@Test
	void isNotUsableWithNoStateOnRecord() {
		when(geoDatasetStateRepository.findInstalled()).thenReturn(Optional.empty());

		Assertions.assertThat(installed.isUsable()).isFalse();
	}

	/**
	 * A run that imported the levels and then died left this row. The boundaries it
	 * wrote are there and are still not a finished installation.
	 */
	@Test
	void isNotUsableWhileTheInstallationIsUnfinished() {
		onRecord(false);

		when(geoAdminBoundaryRepository.count()).thenReturn(52785L);

		Assertions.assertThat(installed.isUsable()).isFalse();
	}

	/**
	 * The case a migration that resets the dataset produces, and the reason the
	 * count is asked of the boundaries rather than remembered on the row: the claim
	 * survives, the polygons do not.
	 */
	@Test
	void isNotUsableWhenTheDatasetSaysCompleteOverAnEmptyTable() {
		onRecord(true);

		when(geoAdminBoundaryRepository.count()).thenReturn(0L);

		Assertions.assertThat(installed.isUsable()).isFalse();
	}

	/**
	 * The count the screen shows is the one in the table, never a stored number: a
	 * remembered total is the first thing to drift away from what is there.
	 */
	@Test
	void reportsTheRecordCountItCountsRatherThanOneItRemembers() {
		onRecord(true);

		when(geoAdminBoundaryRepository.count()).thenReturn(52785L);

		OfflineGeoDatasetStatus status = installed.status();

		Assertions.assertThat(status.available()).isTrue();
		Assertions.assertThat(status.importedRecords()).isEqualTo(52785L);
		Assertions.assertThat(status.version()).isEqualTo("2026-08-16");
		Assertions.assertThat(status.provider()).isEqualTo("geoBoundaries CGAZ");
		Assertions.assertThat(status.importedAt()).isEqualTo(LocalDateTime.parse("2026-08-16T13:08:11"));
	}

	@Test
	void reportsUnavailableWhileTheInstallationIsUnfinished() {
		onRecord(false);

		Assertions.assertThat(installed.status().available()).isFalse();
		Assertions.assertThat(installed.status().directory()).isNotBlank();
	}

	private void onRecord(boolean complete) {
		when(geoDatasetStateRepository.findInstalled())
				.thenReturn(Optional.of(GeoDatasetState.builder().id(GeoDatasetState.SINGLETON_ID)
						.datasetVersion("2026-08-16").source("geoBoundaries").provider("geoBoundaries CGAZ")
						.license("CC BY 4.0").importedAt(LocalDateTime.parse("2026-08-16T13:08:11"))
						.complete(complete).build()));
	}

	/**
	 * The claim survives a reset; the polygons do not. The screen has to say "not
	 * installed" for the same reason an update has to rebuild.
	 */
	@Test
	void reportsUnavailableWhenTheRecordSurvivedAnEmptiedTable() {
		onRecord(true);

		when(geoAdminBoundaryRepository.count()).thenReturn(0L);

		Assertions.assertThat(installed.status().available()).isFalse();
	}

	/**
	 * The size on disk is measured rather than remembered - the files are right
	 * there, and a stored total is one more number that can disagree with them.
	 */
	@Test
	void measuresTheSizeOfTheFilesTheDatasetWasBuiltFrom() throws IOException {
		onRecord(true);

		when(geoAdminBoundaryRepository.count()).thenReturn(1L);

		Path downloads = Files.createDirectories(geodata.resolve("downloads"));

		Files.write(downloads.resolve("adm0.geojson"), new byte[1024]);
		Files.write(downloads.resolve("adm1.geojson"), new byte[2048]);

		Assertions.assertThat(installed.status().sizeBytes()).isEqualTo(3072);
	}

	/** A dataset folder that does not exist yet weighs nothing, and says so. */
	@Test
	void reportsNoSizeWhenTheDatasetFolderDoesNotExist() {
		when(workspaceManager.geodata()).thenReturn(geodata.resolve("absent"));

		onRecord(true);

		when(geoAdminBoundaryRepository.count()).thenReturn(1L);

		Assertions.assertThat(installed.status().sizeBytes()).isZero();
	}
}