package br.com.jorgemelo.nimbusfilemanager.update.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.update.application.dto.ApplicationVersion;

/**
 * The two texts this has to read are written by different hands - the build
 * writes the manifest, a person types the tag - and the comparison decides
 * whether an installation is told there is something newer. Reading a release
 * wrong in the permissive direction would offer an update that does not exist;
 * reading it wrong in the other would leave installations behind forever.
 */
class ApplicationVersionsTest {

	@Test
	void readsTheFourNumbersOfAManifestVersion() {
		ApplicationVersion version = ApplicationVersions.parse("6.0.0.147").orElseThrow();

		Assertions.assertThat(version).isEqualTo(new ApplicationVersion(6, 0, 0, 147));
	}

	/**
	 * Tags are typed with the leading letter by convention, and that is the form
	 * the release endpoint answers with.
	 */
	@Test
	void readsATagWithItsLeadingLetter() {
		ApplicationVersion version = ApplicationVersions.parse("v6.1.2.200").orElseThrow();

		Assertions.assertThat(version).isEqualTo(new ApplicationVersion(6, 1, 2, 200));
	}

	@Test
	void treatsAMissingBuildAsTheFirstOfItsPatch() {
		ApplicationVersion version = ApplicationVersions.parse("6.1.2").orElseThrow();

		Assertions.assertThat(version.build()).isZero();
	}

	@Test
	void ignoresSurroundingWhitespace() {
		Assertions.assertThat(ApplicationVersions.parse("  6.0.0.147  "))
				.contains(new ApplicationVersion(6, 0, 0, 147));
	}

	/**
	 * Anything unparseable is empty rather than a guess: a release this project
	 * did not publish, a tag someone wrote by hand, or a manifest that is simply
	 * absent must all end as "nothing to compare" instead of as a version that
	 * could win a comparison.
	 */
	@Test
	void refusesWhatIsNotAVersion() {
		Assertions.assertThat(ApplicationVersions.parse(null)).isEmpty();
		Assertions.assertThat(ApplicationVersions.parse("")).isEmpty();
		Assertions.assertThat(ApplicationVersions.parse("   ")).isEmpty();
		Assertions.assertThat(ApplicationVersions.parse("latest")).isEmpty();
		Assertions.assertThat(ApplicationVersions.parse("6.0")).isEmpty();
		Assertions.assertThat(ApplicationVersions.parse("6.0.0.147.1")).isEmpty();
		Assertions.assertThat(ApplicationVersions.parse("v6.0.0-rc1")).isEmpty();
	}

	/**
	 * Nine digits is the widest a field can be and still fit an int; a longer run
	 * is refused rather than overflowing into a negative version.
	 */
	@Test
	void refusesANumberTooLongToBeAVersion() {
		Assertions.assertThat(ApplicationVersions.parse("6.0.0.1234567890")).isEmpty();
	}

	@Test
	void offersANewerPatch() {
		Assertions.assertThat(supersedes("6.0.1.150", "6.0.0.147")).isTrue();
	}

	@Test
	void offersANewerMinorEvenWhenItsPatchIsLower() {
		Assertions.assertThat(supersedes("6.1.0.150", "6.0.5.147")).isTrue();
	}

	@Test
	void offersANewerMajorEvenWhenEveryOtherFieldIsLower() {
		Assertions.assertThat(supersedes("7.0.0.150", "6.9.9.900")).isTrue();
	}

	/**
	 * The whole reason the build is excluded. Windows Installer sees three fields,
	 * so these two are the same product version: announcing the second would offer
	 * an upgrade that the machine would refuse to perform as one.
	 */
	@Test
	void refusesToOfferAReleaseThatOnlyChangedItsBuild() {
		Assertions.assertThat(supersedes("6.0.0.200", "6.0.0.147")).isFalse();
	}

	@Test
	void refusesToOfferTheSameVersion() {
		Assertions.assertThat(supersedes("6.0.0.147", "6.0.0.147")).isFalse();
	}

	@Test
	void refusesToOfferAnOlderRelease() {
		Assertions.assertThat(supersedes("5.9.9.900", "6.0.0.147")).isFalse();
		Assertions.assertThat(supersedes("6.0.0.147", "6.0.1.150")).isFalse();
		Assertions.assertThat(supersedes("6.0.1.147", "6.1.0.150")).isFalse();
	}

	/**
	 * What the install script waits for Windows to record. The build is dropped
	 * because Windows Installer never stores it, so a wait that included it would
	 * never be satisfied and every update would sit until its deadline.
	 */
	@Test
	void dropsTheBuildFromTheVersionWindowsRecords() {
		Assertions.assertThat(ApplicationVersions.productVersion("v6.2.7.157")).contains("6.2.7");
		Assertions.assertThat(ApplicationVersions.productVersion("6.2.7.157")).contains("6.2.7");
		Assertions.assertThat(ApplicationVersions.productVersion("6.2.7")).contains("6.2.7");
	}

	@Test
	void hasNoProductVersionForWhatThisProjectDidNotPublish() {
		Assertions.assertThat(ApplicationVersions.productVersion("nightly")).isEmpty();
		Assertions.assertThat(ApplicationVersions.productVersion(null)).isEmpty();
	}

	private static boolean supersedes(String published, String installed) {
		return ApplicationVersions.supersedes(ApplicationVersions.parse(published).orElseThrow(),
				ApplicationVersions.parse(installed).orElseThrow());
	}
}