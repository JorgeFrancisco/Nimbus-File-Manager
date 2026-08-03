package br.com.jorgemelo.nimbusfilemanager.update.infrastructure;

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
import java.time.Duration;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.update.application.ReleaseDownloader;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateInstallProgress;
import lombok.extern.slf4j.Slf4j;

/**
 * Downloads the files of a release over HTTP, the same way the external tools
 * and the database server are fetched.
 *
 * <p>
 * The installer lands beside its final name and is moved into place only once
 * the last byte has arrived, so a download interrupted halfway never leaves a
 * file that a later step could mistake for a complete one. That matters more
 * here than elsewhere: the file after this one is verified by hash and then
 * executed, and the cheapest way to never execute a truncated installer is to
 * never let a truncated installer exist under its own name.
 */
@Slf4j
@Component
public class HttpReleaseDownloader implements ReleaseDownloader {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
	private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(30);
	private static final Duration TEXT_TIMEOUT = Duration.ofSeconds(30);
	private static final String PARTIAL_SUFFIX = ".part";
	private static final int BUFFER = 64 * 1024;

	private final UpdateInstallProgress progress;
	private final HttpClient httpClient;

	public HttpReleaseDownloader(UpdateInstallProgress progress) {
		this.progress = progress;
		this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL).build();
	}

	@Override
	public void download(String url, Path target) throws IOException {
		Path partial = target.resolveSibling(target.getFileName() + PARTIAL_SUFFIX);

		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(DOWNLOAD_TIMEOUT).GET().build();

		HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream());

		if (response.statusCode() != 200) {
			throw new IOException("Download answered HTTP " + response.statusCode() + ": " + url);
		}

		progress.startDownload(response.headers().firstValueAsLong("content-length").orElse(-1));

		try (InputStream source = response.body(); OutputStream sink = Files.newOutputStream(partial)) {
			byte[] buffer = new byte[BUFFER];

			int read;

			while ((read = source.read(buffer)) > 0) {
				sink.write(buffer, 0, read);

				progress.addDownloadedBytes(read);
			}
		}

		Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);

		log.info("Downloaded {} ({} bytes)", target.getFileName(), Files.size(target));
	}

	@Override
	public String readText(String url) throws IOException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TEXT_TIMEOUT).GET().build();

		HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() != 200) {
			throw new IOException("Request answered HTTP " + response.statusCode() + ": " + url);
		}

		return response.body();
	}

	/**
	 * An interrupt is restored and reported as I/O: the caller's answer to both is
	 * the same refusal, and swallowing the flag would leave a thread that no
	 * shutdown can stop.
	 */
	private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
		try {
			return httpClient.send(request, handler);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();

			throw new IOException("Interrupted while fetching " + request.uri(), exception);
		}
	}
}