package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Feature;

/**
 * Parsing behaviour of the streaming GeoJSON reader: which geometries become
 * features, which are skipped, byte reporting and IO failures.
 */
class GeoJsonBoundaryReaderTest {

	private static final String POLYGON = "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[0,1],[1,1],[1,0],[0,0]]]}";
	private static final String MULTI_POLYGON =
			"{\"type\":\"MultiPolygon\",\"coordinates\":[[[[0,0],[0,2],[2,2],[2,0],[0,0]]]]}";
	private static final String POINT = "{\"type\":\"Point\",\"coordinates\":[0,0]}";
	private static final String LINE_STRING = "{\"type\":\"LineString\",\"coordinates\":[[0,0],[1,1]]}";
	private static final String GEOMETRY_COLLECTION = "{\"type\":\"GeometryCollection\",\"geometries\":[]}";
	private static final String POLYGON_WITH_HOLE = "{\"type\":\"Polygon\",\"coordinates\":["
			+ "[[0,0],[0,10],[10,10],[10,0],[0,0]],[[2,2],[2,4],[4,4],[4,2],[2,2]]]}";
	private static final String BAD_RING = "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,1]]]}";

	private final GeoJsonBoundaryReader reader = new GeoJsonBoundaryReader(new ObjectMapper());

	@TempDir
	Path dir;

	@Test
	void readsPolygonsAndMultiPolygonsAndSkipsOtherGeometryTypes() throws IOException {
		Path file = write(collection(feature("Brasil", "BRA", POLYGON), feature("USA", "USA", MULTI_POLYGON),
				feature("City", "BRA", POINT), feature("Road", "BRA", LINE_STRING),
				feature("Mix", "BRA", GEOMETRY_COLLECTION)));

		List<Feature> features = readAll(file);

		Assertions.assertThat(features).hasSize(2);
		Assertions.assertThat(features).extracting(Feature::name).containsExactly("Brasil", "USA");
		Assertions.assertThat(features).extracting(Feature::alpha3).containsExactly("BRA", "USA");
		Assertions.assertThat(features.get(0).geometry().getGeometryType()).isEqualTo("Polygon");
		Assertions.assertThat(features.get(1).geometry().getGeometryType()).isEqualTo("MultiPolygon");
	}

	@Test
	void skipsFeaturesWithoutPropertiesOrGeometryOrCoordinates() throws IOException {
		Path file = write(collection("{\"type\":\"Feature\",\"geometry\":" + POLYGON + "}",
				"{\"type\":\"Feature\",\"properties\":{\"shapeName\":\"X\",\"shapeGroup\":\"BRA\"}}",
				"{\"type\":\"Feature\",\"properties\":{\"shapeName\":\"X\",\"shapeGroup\":\"BRA\"},"
						+ "\"geometry\":{\"type\":\"Polygon\"}}"));

		Assertions.assertThat(readAll(file)).isEmpty();
	}

	@Test
	void skipsUnreadableFeatureWithInvalidRing() throws IOException {
		Path file = write(collection(feature("Broken", "BRA", BAD_RING)));

		Assertions.assertThat(readAll(file)).isEmpty();
	}

	@SuppressWarnings("unchecked")
	@Test
	void parsesPolygonWithInteriorRing() throws IOException {
		Path file = write(collection(feature("Holed", "BRA", POLYGON_WITH_HOLE)));

		List<Feature> features = readAll(file);

		Assertions.assertThat(features).hasSize(1);

		Geometry geometry = features.get(0).geometry();

		Assertions.assertThat(geometry).isInstanceOf(Polygon.class);
		Assertions.assertThat(((Polygon) geometry).getNumInteriorRing()).isEqualTo(1);
	}

	@Test
	void reportsConsumedBytesThroughListener() throws IOException {
		Path file = write(collection(feature("Brasil", "BRA", POLYGON)));

		AtomicLong bytes = new AtomicLong();

		reader.read(file, _ -> {
		}, bytes::addAndGet);

		Assertions.assertThat(bytes.get()).isPositive();
	}

	@Test
	void twoArgumentOverloadReadsWithoutByteListener() throws IOException {
		Path file = write(collection(feature("Brasil", "BRA", POLYGON)));

		List<Feature> features = new ArrayList<>();

		reader.read(file, features::add);

		Assertions.assertThat(features).hasSize(1);
	}

	@Test
	void throwsWhenFileCannotBeRead() {
		Path missing = dir.resolve("does-not-exist.geojson");

		Assertions.assertThatThrownBy(() -> reader.read(missing, _ -> {
		})).isInstanceOf(IllegalStateException.class).hasMessageContaining("Could not read GeoJSON");
	}

	private List<Feature> readAll(Path file) {
		List<Feature> features = new ArrayList<>();

		reader.read(file, features::add);

		return features;
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
}