package br.com.jorgemelo.nimbusfilemanager.database.infrastructure.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.sql.DataSource;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import br.com.jorgemelo.nimbusfilemanager.NimbusFileManagerApplication;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;

/**
 * The worker, in a JVM of its own, saying what database it reached.
 *
 * <p>
 * This is the real application class under the real {@code worker} profile, so
 * the real {@code EmbeddedDatabaseBootstrap} runs and the datasource is built
 * from whatever it published - which is the whole of what this proves. Nothing
 * about the connection is passed in: the process is given a workspace through
 * the environment and has to find the rest itself.
 *
 * <p>
 * It prints what it found and exits. A worker that stayed up would have to be
 * killed, and a test that kills a process cannot tell a clean start from one
 * that was about to fail.
 *
 * <p>
 * <b>Never prints the password.</b> What a failing test needs is which database
 * was reached, and the port answers that.
 */
public final class StandaloneWorkerProbe {

	/** Read by the test out of the child's output. */
	public static final String REACHED = "PROBE-REACHED:";

	public static final String COUNTED = "PROBE-COUNTED:";

	public static final String FAILED = "PROBE-FAILED:";

	private static final String INSERTED_BY_THE_APPLICATION = "the-row-the-application-inserted";

	private StandaloneWorkerProbe() {
	}

	public static void main(String[] args) {
		int status = 0;

		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(NimbusFileManagerApplication.class)
				.web(WebApplicationType.NONE).profiles(NimbusProfiles.WORKER).run(args)) {
			report(context);
		} catch (Exception failure) {
			// The message, never the stack: a datasource failure carries the URL, and
			// the URL is the answer this probe exists to give.
			System.out.println(FAILED + failure.getClass().getSimpleName() + ":" + failure.getMessage());

			status = 1;
		}

		System.exit(status);
	}

	/**
	 * Which database, and what is in it. The count is what separates "reached a
	 * PostgreSQL" from "reached the one the application is using".
	 */
	private static void report(ConfigurableApplicationContext context) throws Exception {
		Environment environment = context.getEnvironment();

		System.out.println(REACHED + environment.getProperty("spring.datasource.url"));

		try (Connection connection = context.getBean(DataSource.class).getConnection();
				PreparedStatement statement = connection
						.prepareStatement("SELECT count(*) FROM execution WHERE claimed_by = ?")) {
			statement.setString(1, INSERTED_BY_THE_APPLICATION);

			try (ResultSet rows = statement.executeQuery()) {
				rows.next();

				System.out.println(COUNTED + rows.getInt(1));
			}
		}
	}
}