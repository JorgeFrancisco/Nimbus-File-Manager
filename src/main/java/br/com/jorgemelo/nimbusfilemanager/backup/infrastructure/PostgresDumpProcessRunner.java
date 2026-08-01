package br.com.jorgemelo.nimbusfilemanager.backup.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogDump;
import br.com.jorgemelo.nimbusfilemanager.backup.application.JdbcUrls;
import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.DatabaseConnection;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs {@code pg_dump} and {@code pg_restore}.
 *
 * <p>
 * The binaries are looked up beside the packaged server first and fall back to
 * the bare command name, which the operating system resolves through PATH -
 * the same two-step the external media tools use. A developer running against
 * their own PostgreSQL gets the one on their PATH; a packaged installation gets
 * the one it shipped, which is guaranteed to match the server's major version.
 */
@Slf4j
@Component
public class PostgresDumpProcessRunner implements CatalogDump {

	/** Where the packaged server lives, relative to the working directory. */
	private static final Path BUNDLED = Path.of("tools", "postgresql", "bin");

	private static final int TIMEOUT_MINUTES = 120;

	private final DatabaseConnection connection;

	/**
	 * Takes the connection Spring actually built, not the configured property.
	 *
	 * <p>
	 * Reading {@code spring.datasource.url} looks equivalent and is not: anything
	 * that supplies the database through connection details rather than through
	 * properties - a Testcontainers {@code @ServiceConnection}, the embedded
	 * cluster picking its own port - leaves the property holding the packaged
	 * default. A dump aimed at the default while the application talks to another
	 * database backs up the wrong one, and restores over it.
	 */
	public PostgresDumpProcessRunner(JdbcConnectionDetails details) {
		this.connection = JdbcUrls.parse(details.getJdbcUrl(), details.getUsername(), details.getPassword());
	}

	/**
	 * The custom format rather than plain SQL: it compresses, and it is what
	 * {@code pg_restore} needs to drop objects selectively instead of replaying a
	 * script that assumes an empty database.
	 */
	@Override
	public boolean dump(Path target) {
		return run("pg_dump", target,
				List.of(executable("pg_dump"), "--format=custom", "--no-owner", "--no-privileges", "--host",
						connection.host(), "--port", Integer.toString(connection.port()), "--username",
						connection.username(), "--file", target.toString(), connection.database()));
	}

	/**
	 * Replaces what is in the database with what the dump holds.
	 *
	 * <p>
	 * {@code --clean --if-exists} drops each object before recreating it, which is
	 * what allows a restore into the database the application is connected to.
	 * Ownership and privileges are skipped because the role that took the dump is
	 * not necessarily the one restoring it - an installation restoring a backup
	 * taken on a developer's server is the normal case, not the exception.
	 */
	@Override
	public boolean restore(Path source) {
		return run("pg_restore", source,
				List.of(executable("pg_restore"), "--clean", "--if-exists", "--no-owner", "--no-privileges", "--host",
						connection.host(), "--port", Integer.toString(connection.port()), "--username",
						connection.username(), "--dbname", connection.database(), source.toString()));
	}

	@Override
	public DatabaseConnection target() {
		return connection;
	}

	/** The packaged binary when it is there, otherwise the bare command. */
	private String executable(String name) {
		for (String candidate : List.of(name, name + ".exe")) {
			Path bundled = BUNDLED.resolve(candidate);

			if (Files.isRegularFile(bundled)) {
				return bundled.toAbsolutePath().toString();
			}
		}

		return name;
	}

	private boolean run(String name, Path beside, List<String> command) {
		Process process = null;

		try {
			// Beside the file being written or read, never in the system temp folder:
			// what these tools print names the database, its host and its tables, and a
			// world-writable directory is readable by everything on the machine.
			Path output = beside.resolveSibling("nimbus-" + name + ".log");

			ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
					.redirectOutput(output.toFile());

			builder.environment().put("PGPASSWORD", connection.password());

			process = builder.start();

			if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
				process.destroyForcibly();

				log.error("{} did not finish within {} minutes", name, TIMEOUT_MINUTES);

				return false;
			}

			return report(name, process.exitValue(), output);
		} catch (IOException exception) {
			log.error("Could not run {}", name, exception);

			return false;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();

			log.warn("Interrupted while running {}", name, exception);

			return false;
		} finally {
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
			}
		}
	}

	/**
	 * {@code pg_restore} exits non-zero on the errors it emits while dropping
	 * objects that are not there, which is routine on a clean restore. The text is
	 * kept either way: on failure it is the only account of what went wrong.
	 */
	private boolean report(String name, int exit, Path output) throws IOException {
		String text = Files.readString(output, StandardCharsets.UTF_8).strip();

		Files.deleteIfExists(output);

		if (exit == 0) {
			log.debug("{} finished: {}", name, text);

			return true;
		}

		log.error("{} failed with exit code {}: {}", name, exit, text);

		return false;
	}
}