package br.com.jorgemelo.nimbusfilemanager.backup.application;

import java.net.URI;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.DatabaseConnection;

/**
 * Splits a PostgreSQL JDBC url into the parts the command-line tools take.
 *
 * <p>
 * {@code pg_dump} wants a host, a port and a database name; the application has
 * a url. Parsing it in one place keeps the two from disagreeing - and the port
 * matters more than it looks, because the embedded server picks its own at
 * first start, so a hard-coded 5432 would dump the wrong database or none.
 */
public final class JdbcUrls {

	private static final String PREFIX = "jdbc:";

	private static final int DEFAULT_PORT = 5432;

	private JdbcUrls() {
	}

	public static DatabaseConnection parse(String url, String username, String password) {
		URI uri = URI.create(url.startsWith(PREFIX) ? url.substring(PREFIX.length()) : url);

		String path = uri.getPath();

		return new DatabaseConnection(host(uri), uri.getPort() > 0 ? uri.getPort() : DEFAULT_PORT,
				path == null || path.length() < 2 ? "" : path.substring(1), username, password);
	}

	private static String host(URI uri) {
		return uri.getHost() == null || uri.getHost().isBlank() ? "127.0.0.1" : uri.getHost();
	}
}