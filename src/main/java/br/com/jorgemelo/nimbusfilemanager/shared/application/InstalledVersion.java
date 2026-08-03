package br.com.jorgemelo.nimbusfilemanager.shared.application;

import java.util.Optional;

/**
 * Which build is running, read from the jar manifest.
 *
 * <p>
 * One place instead of three. The tray menu answers "which one is installed?"
 * without opening anything, a backup records the version that wrote it, and the
 * update check compares it against what is published. All three want the same
 * string, and the first two had each grown their own copy of this call - with
 * different answers for the same absence, which is how a duplicated read starts
 * to drift.
 *
 * <p>
 * Empty outside a packaged build: {@code Implementation-Version} is written into
 * the manifest of the jar, so a run from the IDE or from Maven has no version at
 * all. What that absence means is left to each caller, because the honest
 * answers differ - the menu shows no item rather than the word "unknown", a
 * backup records that word on purpose, and a check with no version of its own
 * has nothing to compare and does not run.
 */
public final class InstalledVersion {

	private InstalledVersion() {
	}

	public static Optional<String> current() {
		return Optional.ofNullable(InstalledVersion.class.getPackage().getImplementationVersion())
				.filter(version -> !version.isBlank());
	}
}