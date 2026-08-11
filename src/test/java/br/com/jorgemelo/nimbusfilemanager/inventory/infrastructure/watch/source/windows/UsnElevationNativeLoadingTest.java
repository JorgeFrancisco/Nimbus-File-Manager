package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.windows;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The Windows glue must not be loaded anywhere but Windows.
 *
 * <p>
 * Proved in a JVM of its own because that is the only place the claim exists:
 * within this one the class may already have been initialised by any Windows
 * test that ran first, and a suite running on Windows cannot see the failure at
 * all - kernel32 loads there. The child is told it is Linux and asked the same
 * question {@code main} asks, and what is asserted is that
 * {@code WindowsKernel32} never appears among the classes it loaded.
 *
 * <p>
 * The defect this holds shut: the volume probe was passed to the decision as an
 * evaluated argument, so it ran before the platform could be ruled out. Loading
 * kernel32 off Windows throws an {@code ExceptionInInitializerError} - an
 * {@code Error}, which the probe's own {@code catch (RuntimeException)} does not
 * hold - and {@code main} died before Spring on every Linux and macOS start. The
 * whole suite passed regardless, because a {@code @SpringBootTest} never goes
 * through {@code main}.
 */
class UsnElevationNativeLoadingTest {

	/** Long enough for a bare JVM to start and print one line. */
	private static final long PROBE_TIMEOUT_SECONDS = 60;

	@Test
	void doesNotLoadTheWindowsGlueWhenTheOperatingSystemIsNotWindows() throws Exception {
		List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
				System.getProperty("java.class.path"), "-Dos.name=Linux", "-verbose:class",
				UsnElevationProbe.class.getName());

		ProcessBuilder builder = new ProcessBuilder(command);

		builder.redirectErrorStream(true);

		Process probe = builder.start();

		String output;

		try (var input = probe.getInputStream()) {
			output = new String(input.readAllBytes());
		}

		Assertions.assertThat(probe.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("the probe never ended")
				.isTrue();

		// The name is read from the class literal rather than written out, so a
		// rename cannot leave this passing for the wrong reason. A class literal
		// does not initialise what it names, which is what makes it safe to write
		// here at all.
		Assertions.assertThat(output).as("the probe answered and did so without relaunching")
				.contains(UsnElevationProbe.ANSWER + false);

		Assertions.assertThat(output).as("kernel32 was reached for on a system that has none")
				.doesNotContain(WindowsKernel32.class.getName());
	}
}