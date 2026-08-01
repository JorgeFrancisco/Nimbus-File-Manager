package br.com.jorgemelo.nimbusfilemanager.database.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.database.application.PostgresArchiveSource;
import lombok.extern.slf4j.Slf4j;

/**
 * Downloads the published PostgreSQL build over HTTP.
 *
 * <p>
 * The url arrives as a value rather than being read from the stored settings,
 * the way the ffmpeg download reads its own: this runs before the
 * {@code DataSource} exists, so the settings table cannot be reached yet -
 * asking the database where to download the database would be a circle. It
 * comes from the application properties instead, which the environment already
 * has by then.
 */
@Slf4j
@Component
public class PostgresBuildSource implements PostgresArchiveSource {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
	private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(60);
	private static final String ARCHIVE_FILE = "postgresql-build.zip";
	private static final int BUFFER = 64 * 1024;

	private final String url;
	private final HttpClient httpClient;

	public PostgresBuildSource(@Value("${nimbus-file-manager.database.download-url:}") String url) {
		this.url = url;
		this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL).build();
	}

	@Override
	public Path download(Path targetFolder) {
		if (url == null || url.isBlank()) {
			log.error("No download url configured for the embedded PostgreSQL");

			return null;
		}

		Path archive = targetFolder.resolve(ARCHIVE_FILE);

		log.info("Downloading the embedded PostgreSQL from {}", url);

		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build();

			HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

			if (response.statusCode() != 200) {
				log.error("The download of the embedded PostgreSQL answered {}", response.statusCode());

				return null;
			}

			try (InputStream body = response.body()) {
				copy(body, archive);
			}

			log.info("Downloaded the embedded PostgreSQL: {} bytes", Files.size(archive));

			return archive;
		} catch (IOException exception) {
			log.error("Could not download the embedded PostgreSQL", exception);

			return null;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();

			log.warn("Interrupted while downloading the embedded PostgreSQL", exception);

			return null;
		}
	}

	private void copy(InputStream body, Path target) throws IOException {
		try (OutputStream output = Files.newOutputStream(target)) {
			byte[] buffer = new byte[BUFFER];

			int read = body.read(buffer);

			while (read >= 0) {
				output.write(buffer, 0, read);

				read = body.read(buffer);
			}
		}
	}
}