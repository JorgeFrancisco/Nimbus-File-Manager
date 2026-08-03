package br.com.jorgemelo.nimbusfilemanager.update.infrastructure;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.UpdateProperties;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.PublishedRelease;

/**
 * Everything that can go wrong out here ends the same way - empty - and that is
 * the point: none of it is a fault of the installation, and none of it may
 * surface as an error to the person using the application. What must not happen
 * is a request going out when the check is switched off, which is the promise
 * the privacy decision rests on.
 */
class GitHubReleaseSourceTest {

	private static final String DOCUMENT = """
			{"tag_name":"v6.1.0.160","draft":false,"html_url":"https://example.invalid/tag",
			 "assets":[{"name":"a.msi","browser_download_url":"https://example.invalid/a.msi","size":10},
			 {"name":"a.msi.sha256","browser_download_url":"https://example.invalid/a.msi.sha256","size":97}]}""";

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final AtomicInteger requests = new AtomicInteger();

	private HttpServer server;
	private String base;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

		server.createContext("/latest", exchange -> answer(exchange, 200, DOCUMENT));
		server.createContext("/missing", exchange -> answer(exchange, 404, "{\"message\":\"Not Found\"}"));
		server.createContext("/limited", exchange -> answer(exchange, 403, "rate limited"));
		server.createContext("/garbage", exchange -> answer(exchange, 200, "<html>not json</html>"));

		server.start();

		base = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	void readsThePublishedRelease() {
		PublishedRelease release = source("/latest").latest().orElseThrow();

		Assertions.assertThat(release.tag()).isEqualTo("v6.1.0.160");
		Assertions.assertThat(release.installerUrl()).isEqualTo("https://example.invalid/a.msi");
	}

	@Test
	void findsNothingWhenTheEndpointHasNoRelease() {
		Assertions.assertThat(source("/missing").latest()).isEmpty();
	}

	@Test
	void findsNothingWhenTheEndpointRefuses() {
		Assertions.assertThat(source("/limited").latest()).isEmpty();
	}

	@Test
	void findsNothingWhenTheAnswerIsNotTheDocument() {
		Assertions.assertThat(source("/garbage").latest()).isEmpty();
	}

	/**
	 * Nothing is listening on that address, which is what being offline looks
	 * like from here. It must be as quiet as any other empty answer.
	 */
	@Test
	void findsNothingWhenNothingAnswers() {
		Assertions.assertThat(new GitHubReleaseSource(new UpdateProperties(true, "http://127.0.0.1:1/latest"),
				objectMapper).latest()).isEmpty();
	}

	/**
	 * The switch is the whole privacy answer: off has to mean no connection at
	 * all, not a connection whose result is discarded.
	 */
	@Test
	void asksNothingWhenTheCheckIsSwitchedOff() {
		Assertions.assertThat(new GitHubReleaseSource(new UpdateProperties(false, base + "/latest"), objectMapper)
				.latest()).isEmpty();

		Assertions.assertThat(requests).hasValue(0);
	}

	@Test
	void asksNothingWithoutAnAddress() {
		Assertions.assertThat(new GitHubReleaseSource(new UpdateProperties(true, "  "), objectMapper).latest())
				.isEmpty();
		Assertions.assertThat(new GitHubReleaseSource(new UpdateProperties(true, null), objectMapper).latest())
				.isEmpty();

		Assertions.assertThat(requests).hasValue(0);
	}

	private GitHubReleaseSource source(String path) {
		return new GitHubReleaseSource(new UpdateProperties(true, base + path), objectMapper);
	}

	private void answer(HttpExchange exchange, int status, String body) throws IOException {
		requests.incrementAndGet();

		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

		exchange.sendResponseHeaders(status, bytes.length);

		try (OutputStream sink = exchange.getResponseBody()) {
			sink.write(bytes);
		}
	}
}