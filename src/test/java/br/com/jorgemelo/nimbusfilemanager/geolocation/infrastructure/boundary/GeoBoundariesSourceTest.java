package br.com.jorgemelo.nimbusfilemanager.geolocation.infrastructure.boundary;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.GeoDatasetProgress;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LeveledBoundaryFile;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.BoundaryDatasetProperties;

/**
 * Acquisition behaviour of the boundary source: conditional downloads (an
 * update only re-transfers a file whose ETag changed on the server) and the
 * territory gap-filling (missing ISO countries fetched individually through the
 * boundary API, tolerant to territories the source has no data for). URLs come
 * from app_setting, mocked here.
 */
class GeoBoundariesSourceTest {

	private static final byte[] BODY = "{\"type\":\"FeatureCollection\",\"features\":[]}"
			.getBytes(StandardCharsets.UTF_8);

	private HttpServer server;
	private final AtomicInteger requests = new AtomicInteger();
	private final AtomicInteger fullDownloads = new AtomicInteger();
	private volatile String currentEtag = "\"v1\"";
	private volatile byte[] currentBody = BODY;

	private final AppSettingService appSettingService = mock(AppSettingService.class);

	@TempDir
	Path workspace;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/adm0", exchange -> {
			requests.incrementAndGet();

			String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");

			if (currentEtag.equals(ifNoneMatch)) {
				exchange.sendResponseHeaders(304, -1);
				exchange.close();

				return;
			}

			fullDownloads.incrementAndGet();

			exchange.getResponseHeaders().add("ETag", currentEtag);
			byte[] body = currentBody;

			exchange.sendResponseHeaders(200, body.length);

			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private String baseUrl() {
		return "http://localhost:" + server.getAddress().getPort();
	}

	private GeoBoundariesSource source() {
		when(appSettingService.stringValue(eq(SettingsConstants.BOUNDARY_ADM0_URL), anyString()))
				.thenReturn(baseUrl() + "/adm0");
		when(appSettingService.stringValue(eq(SettingsConstants.BOUNDARY_ADM1_URL), anyString())).thenReturn("");
		when(appSettingService.stringValue(eq(SettingsConstants.BOUNDARY_ADM2_URL), anyString())).thenReturn("");

		return new GeoBoundariesSource(new BoundaryDatasetProperties(), appSettingService, new ObjectMapper(),
				new GeoDatasetProgress(mock(ExecutionProgressService.class)), Clock.systemDefaultZone());
	}

	@Test
	void shouldReuseUnchangedFileAndRedownloadWhenEtagChanges() {
		GeoBoundariesSource source = source();

		// First update: nothing on disk yet -> full download.
		List<LeveledBoundaryFile> first = source.fetch(workspace).files();

		// The ETag only guards a published file: until commit the download is staged.
		source.commit(workspace);

		Path published = workspace.resolve("downloads").resolve("geoBoundariesCGAZ_ADM0.geojson");

		Assertions.assertThat(first).hasSize(1);
		Assertions.assertThat(first.get(0).kind()).isEqualTo(AdminBoundaryKind.COUNTRY);
		Assertions.assertThat(published).exists().hasBinaryContent(BODY);
		Assertions.assertThat(fullDownloads).hasValue(1);

		// Second update, same ETag on the server -> 304, file reused from disk.
		List<LeveledBoundaryFile> second = source.fetch(workspace).files();

		source.commit(workspace);

		Assertions.assertThat(second).hasSize(1);
		Assertions.assertThat(published).exists().hasBinaryContent(BODY);
		Assertions.assertThat(requests).hasValue(2);
		Assertions.assertThat(fullDownloads).hasValue(1);

		// Dataset changed on the server -> full download again.
		currentEtag = "\"v2\"";
		source.fetch(workspace);

		Assertions.assertThat(fullDownloads).hasValue(2);
	}

	/**
	 * The guarantee an automatic update depends on: a failed one must leave the
	 * dataset that was working exactly as it was. Nothing the operator did not ask
	 * for gets replaced by a download that never completed.
	 */
	@Test
	void keepsThePreviousDatasetWhenTheUpdateIsDiscarded() {
		GeoBoundariesSource source = source();

		Path published = source.fetch(workspace).files().get(0).file();

		source.commit(workspace);

		Path target = workspace.resolve("downloads").resolve("geoBoundariesCGAZ_ADM0.geojson");

		Assertions.assertThat(target).exists().hasBinaryContent(BODY);

		currentEtag = "\"v2\"";
		currentBody = "{\"type\":\"FeatureCollection\",\"features\":[1]}".getBytes(StandardCharsets.UTF_8);

		source.fetch(workspace);
		source.discard(workspace);

		Assertions.assertThat(target).hasBinaryContent(BODY);
		Assertions.assertThat(target.resolveSibling(target.getFileName() + ".new")).doesNotExist();
		Assertions.assertThat(published).isNotEqualTo(target);
	}

	/** And a successful one replaces it, leaving nothing of the old version. */
	@Test
	void replacesThePreviousDatasetWhenTheUpdateIsPublished() {
		GeoBoundariesSource source = source();

		source.fetch(workspace);
		source.commit(workspace);

		byte[] updated = "{\"type\":\"FeatureCollection\",\"features\":[2]}".getBytes(StandardCharsets.UTF_8);

		currentEtag = "\"v3\"";
		currentBody = updated;

		source.fetch(workspace);
		source.commit(workspace);

		Path target = workspace.resolve("downloads").resolve("geoBoundariesCGAZ_ADM0.geojson");

		Assertions.assertThat(target).hasBinaryContent(updated);
		Assertions.assertThat(target.resolveSibling(target.getFileName() + ".new")).doesNotExist();
	}

	@Test
	void shouldFetchAllAvailableLevelsForMissingCountriesAndSkipUnknownTerritories() {
		// API knows all three levels for ABW; AAA is unknown (404) and must be
		// skipped without failing the whole update.
		server.createContext("/api/ABW/ADM0/", exchange -> {
			byte[] json = ("{\"gjDownloadURL\": \"" + baseUrl() + "/gbOpen/geoBoundaries-ABW-ADM0.geojson\"}")
					.getBytes(StandardCharsets.UTF_8);

			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, json.length);

			try (OutputStream out = exchange.getResponseBody()) {
				out.write(json);
			}
		});

		server.createContext("/gbOpen/geoBoundaries-ABW-ADM0.geojson", exchange -> {
			exchange.sendResponseHeaders(200, BODY.length);

			try (OutputStream out = exchange.getResponseBody()) {
				out.write(BODY);
			}
		});

		server.createContext("/api/AAA/ADM0/", exchange -> {
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
		});

		for (String level : List.of("ADM1", "ADM2")) {
			server.createContext("/api/ABW/" + level + "/", exchange -> {
				byte[] json = ("{\"gjDownloadURL\": \"" + baseUrl() + "/gbOpen/geoBoundaries-ABW-" + level
						+ ".geojson\"}").getBytes(StandardCharsets.UTF_8);

				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, json.length);

				try (OutputStream out = exchange.getResponseBody()) {
					out.write(json);
				}
			});

			server.createContext("/gbOpen/geoBoundaries-ABW-" + level + ".geojson", exchange -> {
				exchange.sendResponseHeaders(200, BODY.length);

				try (OutputStream out = exchange.getResponseBody()) {
					out.write(BODY);
				}
			});
		}

		when(appSettingService.stringValue(eq(SettingsConstants.BOUNDARY_GBOPEN_API_URL), anyString()))
				.thenReturn(baseUrl() + "/api/");

		GeoBoundariesSource source = new GeoBoundariesSource(new BoundaryDatasetProperties(), appSettingService,
				new ObjectMapper(), new GeoDatasetProgress(mock(ExecutionProgressService.class)),
				Clock.systemDefaultZone());

		List<LeveledBoundaryFile> files = source.fetchMissingCountries(List.of("ABW", "AAA"), workspace);

		// Published, so the assertion below can read the name the importer will see.
		source.commit(workspace);

		Assertions.assertThat(files).extracting(LeveledBoundaryFile::kind).containsExactly(AdminBoundaryKind.COUNTRY,
				AdminBoundaryKind.STATE, AdminBoundaryKind.MUNICIPALITY);
		Assertions.assertThat(files).extracting(file -> file.file().getFileName().toString()).containsExactly(
				"geoBoundaries-ABW-ADM0.geojson.new", "geoBoundaries-ABW-ADM1.geojson.new",
				"geoBoundaries-ABW-ADM2.geojson.new");
		Assertions.assertThat(workspace.resolve("downloads").resolve("geoBoundaries-ABW-ADM0.geojson")).exists()
				.hasBinaryContent(BODY);
	}

	@Test
	void shouldFetchNothingWhenApiUrlIsBlank() {
		when(appSettingService.stringValue(eq(SettingsConstants.BOUNDARY_GBOPEN_API_URL), anyString())).thenReturn("");

		GeoBoundariesSource source = new GeoBoundariesSource(new BoundaryDatasetProperties(), appSettingService,
				new ObjectMapper(), new GeoDatasetProgress(mock(ExecutionProgressService.class)),
				Clock.systemDefaultZone());

		Assertions.assertThat(source.fetchMissingCountries(List.of("ABW"), workspace)).isEmpty();
	}

	/**
	 * The fact the caller was never told, and the whole reason a worldwide dataset
	 * was rebuilt every single pass.
	 *
	 * <p>
	 * A {@code 304} and a {@code 200} produce the same path on disk, so a caller
	 * handed paths alone has nothing to decide with and can only re-import. Here
	 * the answer carries it: a first acquisition changed everything, a second
	 * against an unchanged server changed nothing, and a third after the server
	 * moved changed again.
	 */
	@Test
	void reportsWhetherAnythingActuallyChangedAtTheSource() {
		GeoBoundariesSource source = source();

		Assertions.assertThat(source.fetch(workspace).changed()).as("nothing on disk yet").isTrue();

		source.commit(workspace);

		Assertions.assertThat(source.fetch(workspace).changed()).as("same ETag, nothing transferred").isFalse();

		source.commit(workspace);

		currentEtag = "\"v2\"";

		Assertions.assertThat(source.fetch(workspace).changed()).as("the server moved").isTrue();
	}
}