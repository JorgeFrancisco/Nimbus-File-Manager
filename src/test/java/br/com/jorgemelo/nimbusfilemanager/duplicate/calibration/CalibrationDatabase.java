package br.com.jorgemelo.nimbusfilemanager.duplicate.calibration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

/**
 * Opens a read-only connection to the running Nimbus database.
 *
 * <p>
 * The port is never assumed. The embedded cluster picks a free one when it
 * starts and records it in {@code <workspace>/database/cluster.properties}
 * beside the generated password, so 5432 is only ever right by accident - and a
 * calibration run that silently connected to some other PostgreSQL on the
 * machine would produce numbers about somebody else's data.
 *
 * <p>
 * The connection is opened read-only and the session is told so: this reads a
 * library that took years to build, and nothing here has any business writing
 * to it. A wrong query then fails instead of changing something.
 */
public final class CalibrationDatabase {

	/** Where the workspace lives unless the run says otherwise. */
	private static final String WORKSPACE_PROPERTY = "nimbus.calibration.workspace";
	private static final String DEFAULT_WORKSPACE = System.getProperty("user.home") + "/Nimbus File Manager/workspace";

	private static final String DATABASE = "nimbus_file_manager";
	private static final String USER = "nimbus_file_manager";

	private CalibrationDatabase() {
	}

	public static Connection openReadOnly() throws Exception {
		Path workspace = Path.of(System.getProperty(WORKSPACE_PROPERTY, DEFAULT_WORKSPACE));
		Path clusterProperties = workspace.resolve("database").resolve("cluster.properties");

		if (!Files.exists(clusterProperties)) {
			throw new IllegalStateException("The cluster is not registered at " + clusterProperties
					+ ". Start the application so the embedded PostgreSQL publishes its port,"
					+ " or point -D" + WORKSPACE_PROPERTY + " at the workspace in use.");
		}

		Properties cluster = read(clusterProperties);
		String url = "jdbc:postgresql://localhost:" + cluster.getProperty("port") + "/" + DATABASE;

		Connection connection = DriverManager.getConnection(url, USER, cluster.getProperty("password"));

		connection.setReadOnly(true);
		connection.setAutoCommit(true);

		return connection;
	}

	private static Properties read(Path path) throws IOException {
		Properties properties = new Properties();

		try (InputStream stream = Files.newInputStream(path)) {
			properties.load(stream);
		}

		return properties;
	}
}