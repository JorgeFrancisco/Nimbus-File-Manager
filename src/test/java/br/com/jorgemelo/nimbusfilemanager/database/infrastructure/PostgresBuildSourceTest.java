package br.com.jorgemelo.nimbusfilemanager.database.infrastructure;

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

import com.sun.net.httpserver.HttpServer;

/**
 * Fetching the server against a real HTTP server. This download decides whether
 * a fresh installation opens at all, and every way it can fail has to end as a
 * reported failure rather than as a truncated archive the installer then tries
 * to unpack.
 */
class PostgresBuildSourceTest {

	private static final byte[] BODY = "a fake postgresql archive".getBytes(StandardCharsets.UTF_8);

	private HttpServer server;

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

	@Test
	void writesWhatTheServerSent() throws IOException {
		Path archive = new PostgresBuildSource(url("/build.zip")).download(target);

		Assertions.assertThat(archive).isNotNull();
		Assertions.assertThat(Files.readAllBytes(archive)).isEqualTo(BODY);
	}

	/**
	 * A refusal is not an archive, and pretending otherwise fails later and in a
	 * place that no longer names the cause.
	 */
	@Test
	void reportsARefusalInsteadOfLeavingAnArchiveBehind() {
		Assertions.assertThat(new PostgresBuildSource(url("/gone.zip")).download(target)).isNull();
	}

	@Test
	void reportsAServerThatIsNotThere() throws IOException {
		int closedPort;

		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}

		Assertions.assertThat(new PostgresBuildSource("http://127.0.0.1:" + closedPort + "/build.zip").download(target))
				.isNull();
	}

	/**
	 * Without a url there is nothing to try. Saying so beats a request to an empty
	 * address, whose failure would name the wrong problem.
	 */
	@Test
	void reportsAMissingUrlWithoutTryingToConnect() {
		Assertions.assertThat(new PostgresBuildSource(null).download(target)).isNull();
		Assertions.assertThat(new PostgresBuildSource("  ").download(target)).isNull();
	}

	private String url(String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}
}