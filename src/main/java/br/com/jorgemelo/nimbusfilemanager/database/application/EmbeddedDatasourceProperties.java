package br.com.jorgemelo.nimbusfilemanager.database.application;

import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants.DATABASE_NAME;
import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants.DATABASE_USER;

import java.util.Map;

import br.com.jorgemelo.nimbusfilemanager.database.application.dto.ClusterConnection;

/**
 * Turns a running cluster into the properties Spring builds its
 * {@code DataSource} from.
 *
 * <p>
 * The host is fixed at the loopback address because that is where the server
 * was told to listen; naming {@code localhost} instead would leave the
 * connection depending on how the machine resolves that name, which on Windows
 * can mean trying IPv6 first against a server bound to IPv4.
 */
public final class EmbeddedDatasourceProperties {

	public static final String URL = "spring.datasource.url";
	public static final String USERNAME = "spring.datasource.username";
	public static final String PASSWORD = "spring.datasource.password";

	private static final String HOST = "127.0.0.1";

	private EmbeddedDatasourceProperties() {
	}

	public static Map<String, Object> from(ClusterConnection connection) {
		return Map.of(URL, "jdbc:postgresql://" + HOST + ":" + connection.port() + "/" + DATABASE_NAME, USERNAME,
				DATABASE_USER, PASSWORD, connection.password());
	}
}