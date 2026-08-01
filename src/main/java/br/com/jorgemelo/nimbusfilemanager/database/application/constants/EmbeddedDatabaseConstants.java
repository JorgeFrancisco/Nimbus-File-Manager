package br.com.jorgemelo.nimbusfilemanager.database.application.constants;

public final class EmbeddedDatabaseConstants {

	/** Folder under the workspace's {@code database} that holds PGDATA. */
	public static final String CLUSTER_FOLDER = "cluster";

	/**
	 * Written by {@code initdb} into every cluster it creates, and by nothing
	 * else: its presence is what tells a real cluster from an empty folder, and
	 * its content is the major version the data belongs to.
	 */
	public static final String VERSION_FILE = "PG_VERSION";

	/** The only major version this build knows how to open. */
	public static final String SUPPORTED_MAJOR_VERSION = "17";

	public static final String DATABASE_NAME = "nimbus_file_manager";
	public static final String DATABASE_USER = "nimbus_file_manager";

	/** Where the port and the generated password survive a restart. */
	public static final String CLUSTER_PROPERTIES = "cluster.properties";

	public static final String PORT_KEY = "port";
	public static final String PASSWORD_KEY = "password";

	/** Turns the embedded cluster on or off, whatever else would have applied. */
	public static final String EMBEDDED_PROPERTY = "nimbus-file-manager.database.embedded";

	/**
	 * The variables that say a database was configured by hand. Only a value
	 * somebody set counts, which is why these are read rather than the resolved
	 * {@code spring.datasource.url} - the packaged properties always define one.
	 */
	public static final String EXTERNAL_HOST_VARIABLE = "NIMBUS_FILE_MANAGER_DB_HOST";

	public static final String EXTERNAL_URL_VARIABLE = "SPRING_DATASOURCE_URL";

	/** Where the server is downloaded from, and whether that happens by itself. */
	public static final String DOWNLOAD_URL_PROPERTY = "nimbus-file-manager.database.download-url";

	public static final String AUTO_INSTALL_PROPERTY = "nimbus-file-manager.database.auto-install";

	private EmbeddedDatabaseConstants() {
	}
}