package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import br.com.jorgemelo.nimbusfilemanager.update.application.dto.ApplicationVersion;

/**
 * Reads a version out of text, and answers whether one supersedes another.
 *
 * <p>
 * The text arrives from two places that are written by different hands: the jar
 * manifest of this build, and the tag of a published release. A tag is typed by
 * a person and carries a leading {@code v}, so both shapes are accepted and
 * anything else is refused rather than guessed at - a release nobody can parse
 * has to be ignored, never treated as newer.
 *
 * <p>
 * <b>The build number is deliberately not part of the comparison.</b> Windows
 * Installer records a product version of three fields, and {@code jpackage} is
 * given {@code MAJOR.MINOR.PATCH} for exactly that reason, so two releases that
 * differ only in the build are the same version to the machine that would
 * install them: the upgrade would not happen, and what does happen instead is
 * decided by Windows rather than by this project. Announcing such a release
 * would be offering an update that cannot be applied. The rule this enforces is
 * already the versioning policy - a build-only bump is refactoring,
 * documentation or a test, which by definition has nothing to deliver to
 * anyone - and enforcing it here means the policy holds even when someone tags
 * one by mistake.
 */
public final class ApplicationVersions {

	private static final Pattern VERSION = Pattern.compile("v?(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})(?:\\.(\\d{1,9}))?");

	private ApplicationVersions() {
	}

	/**
	 * @param text a manifest value ({@code 6.0.0.147}) or a release tag
	 * ({@code v6.0.0.147}); the build is optional
	 * @return empty when the text is absent or is not a version, which is the
	 * answer for anything this project did not publish
	 */
	public static Optional<ApplicationVersion> parse(String text) {
		if (text == null || text.isBlank()) {
			return Optional.empty();
		}

		Matcher matcher = VERSION.matcher(text.trim());

		if (!matcher.matches()) {
			return Optional.empty();
		}

		return Optional.of(new ApplicationVersion(number(matcher.group(1)), number(matcher.group(2)),
				number(matcher.group(3)), number(matcher.group(4))));
	}

	/**
	 * The version Windows records for the installed product: three fields, because
	 * that is all Windows Installer keeps and all {@code jpackage} is given. It is
	 * how a committed installation is recognised - the install script waits for
	 * this string to appear against the product before it reopens the application.
	 *
	 * @return empty for anything this project did not publish
	 */
	public static Optional<String> productVersion(String text) {
		return parse(text).map(version -> version.major() + "." + version.minor() + "." + version.patch());
	}

	/**
	 * Whether {@code published} is worth offering to somebody running
	 * {@code installed}.
	 */
	public static boolean supersedes(ApplicationVersion published, ApplicationVersion installed) {
		if (published.major() != installed.major()) {
			return published.major() > installed.major();
		}

		if (published.minor() != installed.minor()) {
			return published.minor() > installed.minor();
		}

		return published.patch() > installed.patch();
	}

	/**
	 * An absent build counts as zero, so a three-number version compares as the
	 * first build of its patch.
	 */
	private static int number(String group) {
		return group == null ? 0 : Integer.parseInt(group);
	}
}