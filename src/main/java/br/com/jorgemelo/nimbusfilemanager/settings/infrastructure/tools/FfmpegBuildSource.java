package br.com.jorgemelo.nimbusfilemanager.settings.infrastructure.tools;

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

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolArchiveSource;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolInstallProgress;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import lombok.extern.slf4j.Slf4j;

/**
 * Downloads the published ffmpeg build over HTTP. The URL lives in
 * {@code AppSetting} rather than in code so a broken or relocated release can
 * be pointed elsewhere - including an internal mirror - without a new version
 * of the application.
 */
@Slf4j
@Component
public class FfmpegBuildSource implements ExternalToolArchiveSource {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
	private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(30);
	private static final String ARCHIVE_FILE = "ffmpeg-build.zip";
	private static final int BUFFER = 64 * 1024;

	private final AppSettingService appSettingService;
	private final ExternalToolInstallProgress progress;
	private final HttpClient httpClient;

	public FfmpegBuildSource(AppSettingService appSettingService, ExternalToolInstallProgress progress) {
		this.appSettingService = appSettingService;
		this.progress = progress;
		this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL).build();
	}

	@Override
	public Path download(Path targetFolder) {
		String url = appSettingService.stringValue(SettingsConstants.TOOL_DOWNLOAD_URL, "");

		if (url.isBlank()) {
			throw new IllegalStateException("No download URL configured for the external tools.");
		}

		URI uri = URI.create(url);

		Path target = targetFolder.resolve(ARCHIVE_FILE);

		try {
			HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build();

			HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

			if (response.statusCode() != 200) {
				throw new IllegalStateException("Download failed with HTTP " + response.statusCode() + ": " + uri);
			}

			progress.startDownload(response.headers().firstValueAsLong("content-length").orElse(-1));

			copy(response.body(), target);

			log.info("Downloaded the external tools archive ({} bytes)", Files.size(target));

			return target;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();

			throw new IllegalStateException("Could not download " + uri, e);
		} catch (IOException e) {
			throw new IllegalStateException("Could not download " + uri, e);
		}
	}

	/**
	 * Streams to disk instead of buffering: the archive is around 70 MB and
	 * reporting bytes as they land is what turns the wait into a progress bar.
	 */
	private void copy(InputStream body, Path target) throws IOException {
		try (InputStream source = body; OutputStream sink = Files.newOutputStream(target)) {
			byte[] buffer = new byte[BUFFER];

			int read;

			while ((read = source.read(buffer)) > 0) {
				sink.write(buffer, 0, read);

				progress.addDownloadedBytes(read);
			}
		}
	}
}