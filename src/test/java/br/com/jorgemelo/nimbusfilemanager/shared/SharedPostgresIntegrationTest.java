package br.com.jorgemelo.nimbusfilemanager.shared;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One PostgreSQL for every integration test whose writes are undone by the test
 * transaction, and - the part that actually costs money - one Spring
 * {@code ApplicationContext} for all of them.
 *
 * <p>
 * <b>The container was never the expensive half.</b> Measured on this suite, a
 * PostgreSQL container starts in 2,6 s while the application context that talks
 * to it takes 38,3 s. Forty-seven classes each declaring their own container
 * therefore cost far more in contexts than in Docker - and they cost it
 * <em>because</em> of the container: a per-class container means a per-class
 * JDBC URL, a per-class datasource property, and so a per-class context key. The
 * Spring TestContext cache never gets a chance to hit, and every class pays the
 * full 38 s again.
 *
 * <p>
 * So this exists to make the key identical, and the shared container is the
 * means rather than the end. Every subclass inherits exactly these annotations
 * and contributes exactly these properties, which is what lets one context serve
 * all of them.
 *
 * <p>
 * <b>Started once and never stopped, deliberately.</b> The {@code @Testcontainers}
 * extension stops a static container when its class finishes, which for a shared
 * one would mean the first subclass tearing it down for the rest. Starting it
 * from a static initialiser hands the lifecycle to the JVM instead, and
 * Testcontainers' own reaper removes it when the JVM exits - so nothing is left
 * behind on the machine and nothing has to exist before the build starts. This
 * is not {@code testcontainers.reuse.enable}: that would make the build depend on
 * a container surviving between runs, which is not something CI can promise.
 *
 * <p>
 * <b>Who may extend this.</b> Only tests whose every write happens inside the
 * test's own transaction, because the isolation here is the rollback and nothing
 * else. A test that commits - through {@code REQUIRES_NEW}, a worker thread, an
 * async task, its own JDBC connection, or an HTTP round trip into the running
 * server - would leave rows behind for whatever runs next, and the failure would
 * surface as another class's fixture being wrong. Those keep a container of their
 * own.
 */
@SpringBootTest
@Transactional
public abstract class SharedPostgresIntegrationTest {

	/**
	 * One instance for the whole JVM. Not annotated with {@code @Container}: see
	 * the class comment for why the extension's lifecycle is the wrong one here.
	 */
	private static final PostgreSQLContainer<?> POSTGRES = TestPostgres.container();

	static {
		POSTGRES.start();
	}

	/**
	 * The same three values for every subclass, which is the whole point: identical
	 * properties mean an identical context key, and an identical key is a cache
	 * hit.
	 */
	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}
}