package br.com.jorgemelo.nimbusfilemanager.database.application;

import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants.PASSWORD_KEY;
import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants.PORT_KEY;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

import br.com.jorgemelo.nimbusfilemanager.database.application.dto.ClusterConnection;

/**
 * Remembers the port and the password between runs.
 *
 * <p>
 * Both have to survive a restart for opposite reasons. The password is set once
 * when the cluster is created and can never be recomputed - losing it locks the
 * application out of its own database. The port only has to be remembered so
 * that a restart reuses the one that worked, instead of hunting for a free one
 * every time and leaving a trail of half-remembered connection strings.
 */
public class ClusterPropertiesStore {

	/** 24 bytes of randomness, which Base64 turns into 32 printable ones. */
	private static final int PASSWORD_BYTES = 24;

	private final Path file;
	private final SecureRandom random = new SecureRandom();

	public ClusterPropertiesStore(Path file) {
		this.file = file;
	}

	/** What was stored, or {@code null} when there is nothing stored yet. */
	public ClusterConnection load() throws IOException {
		if (!Files.isRegularFile(file)) {
			return null;
		}

		Properties properties = new Properties();

		try (InputStream input = Files.newInputStream(file)) {
			properties.load(input);
		}

		String port = properties.getProperty(PORT_KEY);
		String password = properties.getProperty(PASSWORD_KEY);

		if (port == null || password == null) {
			return null;
		}

		return new ClusterConnection(Integer.parseInt(port), password);
	}

	public void save(ClusterConnection connection) throws IOException {
		Properties properties = new Properties();

		properties.setProperty(PORT_KEY, Integer.toString(connection.port()));
		properties.setProperty(PASSWORD_KEY, connection.password());

		Files.createDirectories(file.getParent());

		try (OutputStream output = Files.newOutputStream(file)) {
			properties.store(output, "Nimbus File Manager embedded database - do not edit while running");
		}
	}

	/**
	 * A password nobody types and nobody has to remember. It exists because the
	 * server refuses to authenticate without one, and it is worth generating
	 * rather than fixing in code: a shipped constant would be the same on every
	 * installation, and this one guards a server that already listens on
	 * loopback only.
	 */
	public String generatePassword() {
		byte[] bytes = new byte[PASSWORD_BYTES];

		random.nextBytes(bytes);

		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}