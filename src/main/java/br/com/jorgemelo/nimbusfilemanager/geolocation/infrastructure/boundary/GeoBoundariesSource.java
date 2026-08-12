package br.com.jorgemelo.nimbusfilemanager.geolocation.infrastructure.boundary;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.GeoDatasetProgress;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary.BoundarySource;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LeveledBoundaryFile;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.BoundaryDatasetProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * Default {@link BoundarySource}: obtains the official geoBoundaries CGAZ
 * global GeoJSON files (ADM0/ADM1/ADM2). If a local folder is configured and
 * contains the files, they are used directly (development and tests); otherwise
 * each level is downloaded from its official URL into workspace/geodata. All
 * hardening (size caps, timeouts, atomic moves, temp cleanup) lives here.
 * Downloads are conditional: the ETag of each file is remembered next to the
 * downloads (etags.properties) and sent as If-None-Match on updates, so an
 * unchanged file is reused from disk instead of re-downloaded - the import
 * still always runs, which is the point of the "update" action. Swapping this
 * bean for an embedded or per-country source changes nothing else.
 */
@Slf4j
@Component
public class GeoBoundariesSource implements BoundarySource {

	static final long MAX_DOWNLOAD_BYTES = 3L * 1024 * 1024 * 1024;

	private static final String DOWNLOADS = "downloads";
	/** Suffix of a file already downloaded but not yet published by commit(). */
	private static final String STAGED_SUFFIX = ".new";
	private static final String ETAGS_FILE = "etags.properties";
	private static final String ADM0_FILE = "geoBoundariesCGAZ_ADM0.geojson";
	private static final String ADM1_FILE = "geoBoundariesCGAZ_ADM1.geojson";
	private static final String ADM2_FILE = "geoBoundariesCGAZ_ADM2.geojson";

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
	private static final Duration REQUEST_TIMEOUT = Duration.ofHours(2);

	private static final Pattern UNSAFE_FILE_NAME_CHARS = Pattern.compile("[^A-Za-z0-9._-]");

	private final BoundaryDatasetProperties properties;
	private final AppSettingService appSettingService;
	private final ObjectMapper objectMapper;
	private final GeoDatasetProgress progress;
	private final HttpClient httpClient;
	/**
	 * ETag of each staged file, applied only when the update is published. A single
	 * update runs at a time - one slot for its type, and the geodata path held for
	 * the length of it - so this never holds two updates at once.
	 *
	 * <p>
	 * Per-process, and that is why a restart mid-update publishes nothing: the map
	 * goes with the process, the staged files stay unpublished beside the previous
	 * dataset, and the next run re-stages over them.
	 */
	private final Map<String, String> pendingEtags = new ConcurrentHashMap<>();
	private final Clock clock;

	public GeoBoundariesSource(BoundaryDatasetProperties properties, AppSettingService appSettingService,
			ObjectMapper objectMapper, GeoDatasetProgress progress, Clock clock) {
		this.properties = properties;
		this.appSettingService = appSettingService;
		this.objectMapper = objectMapper;
		this.progress = progress;
		this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL).build();
		this.clock = clock;
	}

	@Override
	public List<LeveledBoundaryFile> fetch(Path workspaceFolder) {
		List<LeveledBoundaryFile> files = new ArrayList<>();

		// URLs live in app_setting (Settings screen), never hardcoded here.
		add(files, AdminBoundaryKind.COUNTRY, appSettingService.stringValue(SettingsConstants.BOUNDARY_ADM0_URL, ""),
				ADM0_FILE, workspaceFolder);

		add(files, AdminBoundaryKind.STATE, appSettingService.stringValue(SettingsConstants.BOUNDARY_ADM1_URL, ""),
				ADM1_FILE, workspaceFolder);

		add(files, AdminBoundaryKind.MUNICIPALITY,
				appSettingService.stringValue(SettingsConstants.BOUNDARY_ADM2_URL, ""), ADM2_FILE, workspaceFolder);

		if (files.isEmpty()) {
			throw new IllegalStateException("No boundary levels configured to download.");
		}

		return files;
	}

	@Override
	public List<LeveledBoundaryFile> fetchMissingCountries(List<String> alpha3Codes, Path workspaceFolder) {
		String apiBase = appSettingService.stringValue(SettingsConstants.BOUNDARY_GBOPEN_API_URL, "");

		List<LeveledBoundaryFile> files = new ArrayList<>();

		if (apiBase.isBlank()) {
			return files;
		}

		for (String alpha3 : alpha3Codes) {
			try {
				String adm0Url = territoryGeoJsonUrl(apiBase, alpha3, "ADM0");

				if (adm0Url == null) {
					continue; // the source has no data for this territory
				}

				files.add(new LeveledBoundaryFile(AdminBoundaryKind.COUNTRY, download(AdminBoundaryKind.COUNTRY,
						adm0Url, extraFileName(adm0Url), workspaceFolder.resolve(DOWNLOADS))));

				// Deeper levels too, when the territory has them (e.g. Puerto
				// Rico's municipalities) - so city/state show up wherever the
				// source knows them, not only the country name.
				addTerritoryLevel(files, apiBase, alpha3, "ADM1", AdminBoundaryKind.STATE, workspaceFolder);

				addTerritoryLevel(files, apiBase, alpha3, "ADM2", AdminBoundaryKind.MUNICIPALITY, workspaceFolder);
			} catch (Exception e) {
				// One broken territory must never fail the whole update.
				log.warn("Skipping supplemental territory {}: {}", alpha3, e.getMessage());
			}
		}

		return files;
	}

	private void addTerritoryLevel(List<LeveledBoundaryFile> files, String apiBase, String alpha3, String level,
			AdminBoundaryKind kind, Path workspaceFolder) {
		try {
			String url = territoryGeoJsonUrl(apiBase, alpha3, level);

			if (url != null) {
				files.add(new LeveledBoundaryFile(kind,
						download(kind, url, extraFileName(url), workspaceFolder.resolve(DOWNLOADS))));
			}
		} catch (Exception e) {
			// The country polygon was acquired; a missing deeper level only
			// costs detail, never the territory itself.
			log.warn("Skipping {} of supplemental territory {}: {}", level, alpha3, e.getMessage());
		}
	}

	/**
	 * Resolves the GeoJSON download URL of one territory level via the boundary
	 * API, or null when absent.
	 */
	private String territoryGeoJsonUrl(String apiBase, String alpha3, String level) {
		URI uri = URI.create((apiBase.endsWith("/") ? apiBase : apiBase + "/") + alpha3 + "/" + level + "/");

		try {
			HttpRequest request = HttpRequest.newBuilder(uri).timeout(CONNECT_TIMEOUT).GET().build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() == 404) {
				return null;
			}

			if (response.statusCode() != 200) {
				throw new IllegalStateException("Boundary API returned HTTP " + response.statusCode() + ": " + uri);
			}

			JsonNode node = objectMapper.readTree(response.body());

			if (node.isArray()) {
				node = node.isEmpty() ? null : node.get(0);
			}

			JsonNode url = node == null ? null : node.get("gjDownloadURL");

			return url == null || url.isNull() || url.asText().isBlank() ? null : url.asText();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();

			throw new IllegalStateException("Could not query boundary API for " + alpha3, e);
		} catch (IOException e) {
			throw new IllegalStateException("Could not query boundary API for " + alpha3, e);
		}
	}

	/**
	 * Stable per-URL file name inside downloads/, so conditional ETags keep
	 * working.
	 */
	private String extraFileName(String url) {
		String name = UNSAFE_FILE_NAME_CHARS.matcher(url.substring(url.lastIndexOf('/') + 1)).replaceAll("_");

		return name.isBlank() ? "extra-adm0-" + Integer.toHexString(url.hashCode()) + ".geojson" : name;
	}

	@Override
	public String providerLabel() {
		return properties.getProviderLabel();
	}

	@Override
	public String license() {
		return properties.getLicense();
	}

	@Override
	public String sourceTag() {
		return properties.getSourceTag();
	}

	@Override
	public String version() {
		return LocalDate.now(clock).toString();
	}

	/**
	 * The acquisition stage of one level, and the single place that decides where
	 * its file comes from. Three outcomes and no fourth: the file is already on
	 * disk, it has to be downloaded, or the level is not configured at all.
	 *
	 * <p>
	 * Whichever it was, that was the stage - it names itself and is counted. Two of
	 * the three cost no time, and they are still stages for the reason the sequence
	 * is fixed at nine: a run that skipped straight past them would read as a
	 * shorter pipeline, and nobody watching could tell that from a failure.
	 */
	private void add(List<LeveledBoundaryFile> files, AdminBoundaryKind kind, String url, String fileName,
			Path workspaceFolder) {
		Path local = localFile(fileName);

		if (local != null) {
			progress.alreadyAvailable(kind);

			files.add(new LeveledBoundaryFile(kind, local));
		} else if (url == null || url.isBlank()) {
			progress.levelNotConfigured(kind);
		} else {
			files.add(new LeveledBoundaryFile(kind, download(kind, url, fileName, workspaceFolder.resolve(DOWNLOADS))));
		}

		// Reached only when the branch above returned without throwing, which is
		// what keeps a failed acquisition out of the count.
		progress.stageFinished();
	}

	private Path localFile(String fileName) {
		if (properties.getLocalDir() == null || properties.getLocalDir().isBlank()) {
			return null;
		}

		Path candidate = Path.of(properties.getLocalDir()).resolve(fileName);

		return Files.isRegularFile(candidate) ? candidate : null;
	}

	private Path download(AdminBoundaryKind kind, String url, String fileName, Path targetFolder) {
		URI uri = URI.create(url);

		Path target = targetFolder.resolve(fileName).normalize();

		if (!target.startsWith(targetFolder.normalize())) {
			throw new IllegalArgumentException("Download escaped target folder: " + fileName);
		}

		Path temp = null;

		try {
			Files.createDirectories(targetFolder);

			Properties etags = loadEtags(targetFolder);

			String storedEtag = etags.getProperty(fileName);

			HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET();

			// Conditional download: when the previously downloaded file is still
			// on disk, ask the server to skip the body if nothing changed.
			if (storedEtag != null && Files.isRegularFile(target)) {
				request.header("If-None-Match", storedEtag);
			}

			HttpResponse<InputStream> response = httpClient.send(request.build(),
					HttpResponse.BodyHandlers.ofInputStream());

			if (response.statusCode() == 304) {
				response.body().close();

				log.info("{} unchanged on the server (ETag match): reusing the downloaded file", fileName);

				return target;
			}

			if (response.statusCode() != 200) {
				throw new IllegalStateException("Download failed with HTTP " + response.statusCode() + ": " + uri);
			}

			temp = Files.createTempFile(targetFolder, fileName, ".part");

			progress.downloading(kind, response.headers().firstValueAsLong("content-length").orElse(-1));

			copyLimited(response.body(), temp, uri.toString());

			// Staged, not published: the file that resolution reads is only replaced by
			// commit(), once every level downloaded and imported. An update that dies
			// halfway leaves the previous dataset exactly as it was.
			Path staged = target.resolveSibling(fileName + STAGED_SUFFIX);

			Files.move(temp, staged, StandardCopyOption.REPLACE_EXISTING);

			pendingEtags.put(fileName, response.headers().firstValue("ETag").orElse(""));

			log.info("Downloaded {} ({} bytes)", staged.getFileName(), Files.size(staged));

			return staged;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();

			throw new IllegalStateException("Could not download " + uri, e);
		} catch (IOException e) {
			throw new IllegalStateException("Could not download " + uri, e);
		} finally {
			deleteQuietly(temp);
		}
	}

	/**
	 * Publishes what was downloaded: every staged file replaces the one resolution
	 * reads, and only then are the new ETags recorded - so a discarded update never
	 * leaves an ETag claiming a version that is not on disk. Replacing the file is
	 * what removes the previous bytes; nothing older survives a successful update.
	 */
	@Override
	public void commit(Path workspaceFolder) {
		progress.publishing();

		Path downloads = workspaceFolder.resolve(DOWNLOADS);

		if (pendingEtags.isEmpty() || !Files.isDirectory(downloads)) {
			pendingEtags.clear();

			progress.stageFinished();

			return;
		}

		Properties etags = loadEtags(downloads);

		for (Map.Entry<String, String> pending : pendingEtags.entrySet()) {
			Path staged = downloads.resolve(pending.getKey() + STAGED_SUFFIX);

			if (!Files.isRegularFile(staged)) {
				continue;
			}

			try {
				Files.move(staged, downloads.resolve(pending.getKey()), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				throw new IllegalStateException("Could not publish the downloaded file " + staged, e);
			}

			if (pending.getValue().isBlank()) {
				etags.remove(pending.getKey());
			} else {
				etags.setProperty(pending.getKey(), pending.getValue());
			}
		}

		saveEtags(downloads, etags);

		pendingEtags.clear();

		log.info("Geographic dataset files published into {}", downloads);

		progress.stageFinished();
	}

	/** Drops what was downloaded, leaving the previous dataset untouched. */
	@Override
	public void discard(Path workspaceFolder) {
		Path downloads = workspaceFolder.resolve(DOWNLOADS);

		pendingEtags.keySet().forEach(fileName -> deleteQuietly(downloads.resolve(fileName + STAGED_SUFFIX)));

		pendingEtags.clear();
	}

	private Properties loadEtags(Path targetFolder) {
		Properties etags = new Properties();

		Path file = targetFolder.resolve(ETAGS_FILE);

		if (Files.isRegularFile(file)) {
			try (InputStream in = Files.newInputStream(file)) {
				etags.load(in);
			} catch (IOException e) {
				log.warn("Could not read {}; downloads will not be conditional this time", file, e);
			}
		}

		return etags;
	}

	private void saveEtags(Path targetFolder, Properties etags) {
		Path file = targetFolder.resolve(ETAGS_FILE);

		try (OutputStream out = Files.newOutputStream(file)) {
			etags.store(out, "ETag of each downloaded boundary file; enables conditional re-downloads");
		} catch (IOException e) {
			log.warn("Could not persist {}; the next update will re-download everything", file, e);
		}
	}

	private void copyLimited(InputStream in, Path target, String source) throws IOException {
		byte[] buffer = new byte[64 * 1024];
		long total = 0;

		try (OutputStream out = Files.newOutputStream(target)) {
			int read;

			while ((read = in.read(buffer)) >= 0) {
				total += read;

				if (total > MAX_DOWNLOAD_BYTES) {
					throw new IOException("Content exceeds size limit (" + MAX_DOWNLOAD_BYTES + " bytes): " + source);
				}

				out.write(buffer, 0, read);
				progress.addDownloadedBytes(read);
			}
		}
	}

	private void deleteQuietly(Path file) {
		if (file == null) {
			return;
		}

		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			log.warn("Could not delete temporary file {}", file, e);
		}
	}
}