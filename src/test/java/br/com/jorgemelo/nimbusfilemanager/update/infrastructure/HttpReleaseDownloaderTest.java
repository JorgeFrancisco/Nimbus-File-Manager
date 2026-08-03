package br.com.jorgemelo.nimbusfilemanager.update.infrastructure;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateInstallProgress;

/**
 * Exercised against a real server on the loopback interface rather than against
 * a mocked client, because what is worth proving is the transfer itself: that a
 * refusal raises instead of leaving an empty file, and that nothing lands under
 * the final name until the last byte has arrived. A mocked client would assert
 * that the code calls itself correctly and prove neither.
 */
class HttpReleaseDownloaderTest {

	private static final byte[] CONTENT = "the installer bytes".getBytes(StandardCharsets.UTF_8);

	private final UpdateInstallProgress progress = new UpdateInstallProgress();

	private HttpServer server;
	private String base;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

		server.createContext("/installer", exchange -> respond(exchange, 200, CONTENT));
		server.createContext("/text",
				exchange -> respond(exchange, 200, "a checksum".getBytes(StandardCharsets.UTF_8)));
		server.createContext("/missing", exchange -> respond(exchange, 404, new byte[0]));

		server.start();

		base = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	void downloadsEveryByteToTheNameItWasGiven(@TempDir Path folder) throws IOException {
		Path target = folder.resolve("a.msi");

		new HttpReleaseDownloader(progress).download(base + "/installer", target);

		Assertions.assertThat(target).exists().hasBinaryContent(CONTENT);
	}

	/**
	 * The partial file is an implementation detail that must not outlive the
	 * transfer: a leftover would sit beside the installer under a name nothing
	 * cleans up.
	 */
	@Test
	void leavesNoPartialFileBehind(@TempDir Path folder) throws IOException {
		new HttpReleaseDownloader(progress).download(base + "/installer", folder.resolve("a.msi"));

		Assertions.assertThat(folder.resolve("a.msi.part")).doesNotExist();
	}

	/**
	 * A refusal has to raise rather than leave a zero-byte file, which the next
	 * step would hash and compare as if it were a download.
	 */
	@Test
	void refusesAStatusThatIsNotSuccess(@TempDir Path folder) {
		Path target = folder.resolve("a.msi");

		Assertions.assertThatThrownBy(() -> new HttpReleaseDownloader(progress).download(base + "/missing", target))
				.isInstanceOf(IOException.class).hasMessageContaining("404");

		Assertions.assertThat(target).doesNotExist();
	}

	@Test
	void readsASmallTextFile() throws IOException {
		Assertions.assertThat(new HttpReleaseDownloader(progress).readText(base + "/text")).isEqualTo("a checksum");
	}

	@Test
	void refusesTextThatAnsweredWithAnError() {
		Assertions.assertThatThrownBy(() -> new HttpReleaseDownloader(progress).readText(base + "/missing"))
				.isInstanceOf(IOException.class).hasMessageContaining("404");
	}

	private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
		exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);

		try (OutputStream sink = exchange.getResponseBody()) {
			sink.write(body);
		}
	}
}