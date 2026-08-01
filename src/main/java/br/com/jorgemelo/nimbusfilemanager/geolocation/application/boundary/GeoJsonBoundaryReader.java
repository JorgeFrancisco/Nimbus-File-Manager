package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Feature;
import lombok.extern.slf4j.Slf4j;

/**
 * Streams a (potentially very large) GeoJSON FeatureCollection - one feature at
 * a time - so the global geoBoundaries files never have to fit in memory. For
 * each feature it emits the geoBoundaries name ({@code shapeName}), the alpha-3
 * country ({@code shapeGroup}) and the JTS geometry. Points, lines and geometry
 * collections are skipped; unreadable features are logged and skipped.
 */
@Slf4j
@Component
public class GeoJsonBoundaryReader {

	private final ObjectMapper objectMapper;
	private final GeometryFactory geometryFactory = new GeometryFactory();

	public GeoJsonBoundaryReader(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/** Invokes {@code consumer} for every readable polygon feature in the file. */
	public void read(Path file, Consumer<Feature> consumer) {
		read(file, consumer, _ -> {
		});
	}

	/**
	 * Same as {@link #read(Path, Consumer)}, additionally reporting every chunk of
	 * bytes consumed from the file to {@code bytesRead} - the caller can turn that
	 * into a percentage, since the file size is known.
	 */
	public void read(Path file, Consumer<Feature> consumer, LongConsumer bytesRead) {
		try (InputStream in = counting(Files.newInputStream(file), bytesRead);
				JsonParser parser = objectMapper.getFactory().createParser(in)) {
			while (parser.nextToken() != null) {
				if (parser.currentToken() == JsonToken.FIELD_NAME && "features".equals(parser.currentName())) {
					parser.nextToken(); // START_ARRAY

					while (parser.nextToken() == JsonToken.START_OBJECT) {
						JsonNode feature = objectMapper.readTree(parser);

						emit(feature, consumer);
					}

					return;
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not read GeoJSON boundary file: " + file, e);
		}
	}

	private InputStream counting(InputStream in, LongConsumer bytesRead) {
		return new FilterInputStream(in) {

			@Override
			public int read() throws IOException {
				int value = super.read();

				if (value >= 0) {
					bytesRead.accept(1);
				}

				return value;
			}

			@Override
			public int read(byte[] buffer, int offset, int length) throws IOException {
				int read = super.read(buffer, offset, length);

				if (read > 0) {
					bytesRead.accept(read);
				}

				return read;
			}
		};
	}

	private void emit(JsonNode feature, Consumer<Feature> consumer) {
		JsonNode properties = feature.get("properties");

		JsonNode geometryNode = feature.get("geometry");

		if (properties == null || geometryNode == null) {
			return;
		}

		String name = text(properties, "shapeName");

		String alpha3 = text(properties, "shapeGroup");

		try {
			Geometry geometry = toGeometry(geometryNode);

			if (geometry != null && !geometry.isEmpty()) {
				consumer.accept(new Feature(name, alpha3, geometry));
			}
		} catch (RuntimeException e) {
			log.debug("Skipping unreadable feature {}", name, e);
		}
	}

	private Geometry toGeometry(JsonNode geometry) {
		String type = text(geometry, "type");

		JsonNode coordinates = geometry.get("coordinates");

		if (type == null || coordinates == null) {
			return null;
		}

		return switch (type) {
		case "Polygon" -> polygon(coordinates);
		case "MultiPolygon" -> multiPolygon(coordinates);
		default -> null;
		};
	}

	private Geometry multiPolygon(JsonNode polygons) {
		Polygon[] parts = new Polygon[polygons.size()];

		for (int i = 0; i < polygons.size(); i++) {
			parts[i] = polygon(polygons.get(i));
		}

		return geometryFactory.createMultiPolygon(parts);
	}

	private Polygon polygon(JsonNode rings) {
		LinearRing shell = ring(rings.get(0));

		LinearRing[] holes = new LinearRing[Math.max(0, rings.size() - 1)];

		for (int i = 1; i < rings.size(); i++) {
			holes[i - 1] = ring(rings.get(i));
		}

		return geometryFactory.createPolygon(shell, holes);
	}

	private LinearRing ring(JsonNode positions) {
		Coordinate[] coordinates = new Coordinate[positions.size()];

		for (int i = 0; i < positions.size(); i++) {
			JsonNode position = positions.get(i);

			coordinates[i] = new Coordinate(position.get(0).asDouble(), position.get(1).asDouble());
		}

		return geometryFactory.createLinearRing(coordinates);
	}

	private String text(JsonNode node, String field) {
		JsonNode value = node.get(field);

		return value == null || value.isNull() ? null : value.asText();
	}
}