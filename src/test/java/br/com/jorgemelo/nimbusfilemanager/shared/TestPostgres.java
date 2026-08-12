package br.com.jorgemelo.nimbusfilemanager.shared;

import java.util.UUID;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The PostgreSQL every integration test runs against, named after the test that
 * asked for it.
 *
 * <p>
 * Docker names an unnamed container after two random words, so a machine running
 * this suite fills with {@code eager_hopper} and {@code nostalgic_wozniak} and
 * the only way to tell which one belongs to which test is to inspect each in
 * turn. Naming them turns that list into something readable: they sort together
 * under one prefix, and each says what it is for.
 *
 * <p>
 * <b>The caller is not asked who it is.</b> The container is created in a test
 * class's static initialiser, so the class is already on the stack - taking it
 * from there rather than from a parameter means fifty-one call sites that cannot
 * pass the wrong name, and none that has to be updated when a class is renamed.
 *
 * <p>
 * <b>The suffix is not decoration.</b> Docker refuses to create a second
 * container with a name already in use, so a fixed name would turn two
 * concurrent runs of the same class - a rerun started while the first is still
 * shutting down, two branches building at once, the parallel execution this
 * suite already uses - into a failure with no obvious cause. The random tail
 * costs nothing and removes that entirely.
 */
public final class TestPostgres {

	/** Every container this suite starts, so `docker ps` groups them together. */
	private static final String PREFIX = "nimbus-it-";

	/**
	 * Named here rather than at each call site: the version is a property of the
	 * suite, and fifty-one copies of it is fifty-one places to miss on an upgrade.
	 */
	private static final String IMAGE = "postgres:17-alpine";

	private static final String INTEGRATION_TEST_SUFFIX = "IntegrationTest";
	private static final String TEST_SUFFIX = "Test";

	private TestPostgres() {
	}

	/**
	 * A container named after the class calling this, ready to start or to keep
	 * configuring.
	 */
	@SuppressWarnings("resource")
	public static PostgreSQLContainer<?> container() {
		String name = nameFor(caller());

		return new PostgreSQLContainer<>(IMAGE).withCreateContainerCmdModifier(command -> command.withName(name));
	}

	/**
	 * {@code nimbus-it-CatalogBackup-7d21a4e0}: the prefix everything shares, what
	 * the test is about, and enough randomness for two of them to coexist.
	 *
	 * <p>
	 * The class name loses the part every one of them repeats - a list where
	 * every entry ends in {@code IntegrationTest} spends its width saying so - and
	 * keeps to characters Docker accepts in a name.
	 */
	static String nameFor(Class<?> testClass) {
		return PREFIX + shortNameOf(testClass) + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private static String shortNameOf(Class<?> testClass) {
		String simpleName = testClass.getSimpleName();

		String trimmed = simpleName.endsWith(INTEGRATION_TEST_SUFFIX)
				? simpleName.substring(0, simpleName.length() - INTEGRATION_TEST_SUFFIX.length())
				: trimTest(simpleName);

		// A class named exactly after a suffix would trim away to nothing, and a
		// container called "nimbus-it--7d21a4e0" says less than the class name does.
		return trimmed.isBlank() ? simpleName : trimmed;
	}

	private static String trimTest(String simpleName) {
		return simpleName.endsWith(TEST_SUFFIX) ? simpleName.substring(0, simpleName.length() - TEST_SUFFIX.length())
				: simpleName;
	}

	/**
	 * The class whose static initialiser is starting a container, which is the
	 * first frame that is not this helper.
	 *
	 * <p>
	 * The {@code <Class<?>>} on the map is not decoration: without it the stream
	 * carries the captured type of {@code getDeclaringClass()}, and a capture
	 * accepts no class literal at all - so the fallback below stops compiling under
	 * ECJ, which captures where javac infers the wildcard back.
	 */
	private static Class<?> caller() {
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
				.walk(frames -> frames.<Class<?>>map(StackWalker.StackFrame::getDeclaringClass)
						.filter(candidate -> candidate != TestPostgres.class).findFirst())
				.orElse(TestPostgres.class);
	}
}