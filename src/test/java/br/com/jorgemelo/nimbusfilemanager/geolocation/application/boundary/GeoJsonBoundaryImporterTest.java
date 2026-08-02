package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.GeoDatasetProgress;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LeveledBoundaryFile;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.geolocation.infrastructure.persistence.GeoAdminBoundaryImportRepository;

/**
 * Import behaviour of the boundary loader against a mocked import repository:
 * it clears the table, skips unknown countries and blank names, repairs
 * mojibake, flushes in batches and fails a full import that yields no rows.
 */
class GeoJsonBoundaryImporterTest {

	private static final String POLYGON = "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[0,1],[1,1],[1,0],[0,0]]]}";

	private final GeoAdminBoundaryImportRepository boundaryRepository = mock(GeoAdminBoundaryImportRepository.class);
	private final GeoJsonBoundaryReader reader = new GeoJsonBoundaryReader(new ObjectMapper());
	private final GeoDatasetProgress progress = new GeoDatasetProgress();

	// Snapshotted at call time: the importer reuses and clears the batch list
	// after each flush, so capturing the live reference would read as empty.
	private final List<SqlParameterSource> insertedRows = new ArrayList<>();

	private GeoJsonBoundaryImporter importer;

	@TempDir
	Path dir;

	@BeforeEach
	void setUp() {
		doAnswer(invocation -> {
			List<SqlParameterSource> batch = invocation.getArgument(0);

			insertedRows.addAll(batch);

			return null;
		}).when(boundaryRepository).batchInsert(any());

		importer = new GeoJsonBoundaryImporter(boundaryRepository, reader, progress);
	}

	@Test
	void importDatasetClearsTableAndInsertsOnlyResolvableCountries() throws IOException {
		Path file = write(collection(feature("Brasil", "BRA", POLYGON), feature("EUA", "USA", POLYGON),
				feature("Unknown", "XXX", POLYGON), feature("", "BRA", POLYGON)));

		long total = importer.importDataset(List.of(new LeveledBoundaryFile(AdminBoundaryKind.COUNTRY, file)), "TEST",
				"v1");

		Assertions.assertThat(total).isEqualTo(2);

		verify(boundaryRepository).deleteAll();

		Assertions.assertThat(insertedRows).hasSize(2);
		Assertions.assertThat(insertedRows.get(0).getValue("kind")).isEqualTo("COUNTRY");
		Assertions.assertThat(insertedRows.get(0).getValue("countryCode")).isEqualTo("BR");
		Assertions.assertThat(insertedRows.get(0).getValue("source")).isEqualTo("TEST");
		Assertions.assertThat(insertedRows.get(0).getValue("datasetVersion")).isEqualTo("v1");
		Assertions.assertThat(insertedRows.get(0).getValue("geometry")).isInstanceOf(byte[].class);
	}

	@Test
	void importDatasetFailsWhenNoPolygonsAreImportable() throws IOException {
		Path file = write(collection(feature("Unknown", "XXX", POLYGON)));

		List<LeveledBoundaryFile> files = List.of(new LeveledBoundaryFile(AdminBoundaryKind.COUNTRY, file));

		Assertions.assertThatThrownBy(() -> importer.importDataset(files, "TEST", "v1"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("no importable polygons");
	}

	@Test
	void importDatasetRepairsMojibakeNames() throws IOException {
		// "RegiÃ³n" (double-encoded UTF-8) must be repaired to "Región".
		Path file = write(collection(feature("RegiÃ³n", "BRA", POLYGON)));

		importer.importDataset(List.of(new LeveledBoundaryFile(AdminBoundaryKind.COUNTRY, file)), "TEST", "v1");

		Assertions.assertThat(insertedRows.get(0).getValue("name")).isEqualTo("Región");
	}

	/**
	 * The repair only makes sense for text that arrived as Latin-1 bytes. A name
	 * already carrying characters outside that range is real UTF-8, and putting it
	 * through the repair would corrupt what is correct.
	 */
	@Test
	void importDatasetLeavesNamesThatAreAlreadyRealUnicodeAlone() throws IOException {
		Path file = write(collection(feature("Hà Nội", "VNM", POLYGON)));

		importer.importDataset(List.of(new LeveledBoundaryFile(AdminBoundaryKind.COUNTRY, file)), "TEST", "v1");

		Assertions.assertThat(insertedRows.get(0).getValue("name")).isEqualTo("Hà Nội");
	}

	/**
	 * A nameless feature is skipped instead of stored unnamed, and one named
	 * beyond what the column takes is cut to fit - a whole country file is not
	 * worth failing over one long label.
	 */
	@Test
	void importDatasetSkipsNamelessFeaturesAndTrimsOverlongNames() throws IOException {
		Path file = write(collection(namelessFeature("BRA", POLYGON), feature("A".repeat(250), "BRA", POLYGON)));

		long total = importer.importDataset(List.of(new LeveledBoundaryFile(AdminBoundaryKind.COUNTRY, file)), "TEST",
				"v1");

		Assertions.assertThat(total).isEqualTo(1);
		Assertions.assertThat(insertedRows).hasSize(1);
		Assertions.assertThat((String) insertedRows.get(0).getValue("name")).hasSize(200);
	}

	@Test
	void importExtraDoesNotClearTableAndToleratesEmptyInput() throws IOException {
		Assertions.assertThat(importer.importExtra(List.of(), "TEST", "v1")).isZero();

		verify(boundaryRepository, never()).deleteAll();

		Path file = write(collection(feature("Brasil", "BRA", POLYGON)));

		long total = importer.importExtra(List.of(new LeveledBoundaryFile(AdminBoundaryKind.COUNTRY, file)), "TEST",
				"v1");

		Assertions.assertThat(total).isEqualTo(1);

		verify(boundaryRepository, never()).deleteAll();
	}

	@Test
	void importDatasetFlushesInBatchesOverThreshold() throws IOException {
		List<String> features = new ArrayList<>();

		for (int i = 0; i < 501; i++) {
			features.add(feature("Region " + i, "BRA", POLYGON));
		}

		Path file = write(collection(features.toArray(String[]::new)));

		long total = importer.importDataset(List.of(new LeveledBoundaryFile(AdminBoundaryKind.COUNTRY, file)), "TEST",
				"v1");

		Assertions.assertThat(total).isEqualTo(501);

		// One flush at 500, one for the remaining row.
		verify(boundaryRepository, times(2)).batchInsert(any());
	}

	private Path write(String json) throws IOException {
		Path file = dir.resolve("boundary-" + Math.abs(json.hashCode()) + ".geojson");

		Files.writeString(file, json);

		return file;
	}

	private static String collection(String... features) {
		return "{\"type\":\"FeatureCollection\",\"features\":[" + String.join(",", features) + "]}";
	}

	private static String feature(String name, String group, String geometry) {
		return "{\"type\":\"Feature\",\"properties\":{\"shapeName\":\"" + name + "\",\"shapeGroup\":\"" + group
				+ "\"},\"geometry\":" + geometry + "}";
	}

	private static String namelessFeature(String group, String geometry) {
		return "{\"type\":\"Feature\",\"properties\":{\"shapeGroup\":\"" + group + "\"},\"geometry\":" + geometry + "}";
	}
}