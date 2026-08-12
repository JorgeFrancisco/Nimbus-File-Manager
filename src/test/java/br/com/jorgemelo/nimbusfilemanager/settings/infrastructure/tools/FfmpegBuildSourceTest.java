package br.com.jorgemelo.nimbusfilemanager.settings.infrastructure.tools;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpServer;

import br.com.jorgemelo.nimbusfilemanager.execution.application.Progress;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolInstallProgress;

/**
 * Download behaviour of the tools archive against a real HTTP server: the file
 * lands on disk with the bytes the server sent, progress advances while it is
 * streaming, and a refusal surfaces instead of leaving a truncated archive for
 * the installer to choke on.
 */
class FfmpegBuildSourceTest {

	private static final byte[] BODY = "a fake ffmpeg archive".getBytes(StandardCharsets.UTF_8);

	private HttpServer server;

	private final ExternalToolInstallProgress progress = new ExternalToolInstallProgress(Progress.estimator());

	@TempDir
	Path target;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);

		server.createContext("/build.zip", exchange -> {
			exchange.sendResponseHeaders(200, BODY.length);

			try (OutputStream body = exchange.getResponseBody()) {
				body.write(BODY);
			}

			exchange.close();
		});

		server.createContext("/gone.zip", exchange -> {
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
		});

		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private String url(String path) {
		return "http://localhost:" + server.getAddress().getPort() + path;
	}

	private FfmpegBuildSource source(String url) {
		return new FfmpegBuildSource(url, progress);
	}

	@Test
	void writesTheArchiveItDownloadedAndReportsTheProgress() throws IOException {
		Path archive = source(url("/build.zip")).download(target);

		Assertions.assertThat(archive).exists().hasParent(target);
		Assertions.assertThat(Files.readAllBytes(archive)).isEqualTo(BODY);

		Assertions.assertThat(progress.snapshot().downloading()).isTrue();
		Assertions.assertThat(progress.snapshot().bytesDone()).isEqualTo(BODY.length);
		Assertions.assertThat(progress.snapshot().percent()).isEqualTo(100D);
	}

	@Test
	void failsWithTheStatusCodeWhenTheServerRefuses() {
		FfmpegBuildSource source = source(url("/gone.zip"));

		Assertions.assertThatIllegalStateException().isThrownBy(() -> source.download(target))
				.withMessageContaining("404");
	}

	/**
	 * Offline, or a mirror that no longer answers: the reason reaches the screen
	 * instead of a silent no-op that leaves the tools missing.
	 */
	@Test
	void failsWhenTheServerCannotBeReached() throws IOException {
		int closedPort;

		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}

		FfmpegBuildSource source = source("http://localhost:" + closedPort + "/build.zip");

		Assertions.assertThatIllegalStateException().isThrownBy(() -> source.download(target))
				.withMessageContaining("Could not download");
	}

	/**
	 * A blank address is the state of a configuration whose property was cleared,
	 * and a null one of a configuration that never declared it: both have to say
	 * so, rather than attempt a request against an empty URI or fail on the null.
	 */
	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "  " })
	void failsWhenNoAddressIsConfigured(String address) {
		FfmpegBuildSource source = source(address);

		Assertions.assertThatIllegalStateException().isThrownBy(() -> source.download(target))
				.withMessageContaining("No download URL");
	}
}