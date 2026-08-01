package br.com.jorgemelo.nimbusfilemanager.database.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.database.application.dto.ClusterConnection;

/**
 * The connection the application is handed after its own cluster starts. The
 * port is decided at runtime, so these properties are the only thing standing
 * between a started server and an application that cannot find it.
 */
class EmbeddedDatasourcePropertiesTest {

	/**
	 * The loopback address rather than {@code localhost}: the server was told to
	 * listen on 127.0.0.1, and a name would leave the connection depending on how
	 * the machine resolves it - on Windows, often IPv6 first, against a server that
	 * is not there.
	 */
	@Test
	void pointsTheApplicationAtTheLoopbackAddressAndTheRunningPort() {
		var properties = EmbeddedDatasourceProperties.from(new ClusterConnection(6789, "secret"));

		Assertions.assertThat(properties).containsEntry(EmbeddedDatasourceProperties.URL,
				"jdbc:postgresql://127.0.0.1:6789/nimbus_file_manager");
	}

	@Test
	void carriesTheGeneratedCredentials() {
		var properties = EmbeddedDatasourceProperties.from(new ClusterConnection(6789, "secret"));

		Assertions.assertThat(properties).containsEntry(EmbeddedDatasourceProperties.USERNAME, "nimbus_file_manager")
				.containsEntry(EmbeddedDatasourceProperties.PASSWORD, "secret");
	}
}