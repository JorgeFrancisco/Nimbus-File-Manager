package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.windows;

/**
 * Runs the decision {@code main} takes before Spring, in a JVM of its own, so
 * what is observed is real class loading rather than a stand-in for it. Started
 * by {@link UsnElevationNativeLoadingTest} with an operating system name that is
 * not Windows.
 */
public final class UsnElevationProbe {

	static final String ANSWER = "relaunched=";

	private UsnElevationProbe() {
	}

	public static void main(String[] arguments) {
		System.out.println(ANSWER + WindowsUsnElevation.relaunchIfNeeded(arguments));
	}
}