package br.com.jorgemelo.nimbusfilemanager.update.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.update.application.dto.AvailableUpdate;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.PublishedRelease;

/**
 * Whether an installation is told there is something newer. Both mistakes are
 * expensive in their own way - offering an update that cannot be installed
 * wastes a download and a restart, while never offering one leaves the
 * installed version to become the eternal version.
 */
class UpdateAvailabilityTest {

	@Test
	void offersAReleaseThatSupersedesTheInstalledVersion() {
		AvailableUpdate update = UpdateAvailability.decide("6.0.0.147", release("v6.1.0.160")).orElseThrow();

		Assertions.assertThat(update.installed()).isEqualTo("6.0.0.147");
		Assertions.assertThat(update.published()).isEqualTo("v6.1.0.160");
		Assertions.assertThat(update.release().installerUrl()).isEqualTo("https://example.invalid/a.msi");
	}

	@Test
	void offersNothingWhenTheInstallationIsAlreadyCurrent() {
		Assertions.assertThat(UpdateAvailability.decide("6.1.0.160", release("v6.1.0.160"))).isEmpty();
	}

	@Test
	void offersNothingWhenThePublishedReleaseIsOlder() {
		Assertions.assertThat(UpdateAvailability.decide("6.1.0.160", release("v6.0.9.150"))).isEmpty();
	}

	/**
	 * The build is invisible to Windows Installer, so a release that only moved it
	 * is not an upgrade anybody could apply.
	 */
	@Test
	void offersNothingWhenOnlyTheBuildMoved() {
		Assertions.assertThat(UpdateAvailability.decide("6.1.0.160", release("v6.1.0.999"))).isEmpty();
	}

	/**
	 * A run from the IDE or from Maven has no manifest. Treating that as an
	 * ancient version would offer to overwrite a working copy with a release.
	 */
	@Test
	void offersNothingToARunThatDoesNotKnowItsOwnVersion() {
		Assertions.assertThat(UpdateAvailability.decide(null, release("v9.9.9.999"))).isEmpty();
		Assertions.assertThat(UpdateAvailability.decide("", release("v9.9.9.999"))).isEmpty();
		Assertions.assertThat(UpdateAvailability.decide("unknown", release("v9.9.9.999"))).isEmpty();
	}

	@Test
	void offersNothingWhenTheTagCannotBeRead() {
		Assertions.assertThat(UpdateAvailability.decide("6.0.0.147", release("nightly"))).isEmpty();
		Assertions.assertThat(UpdateAvailability.decide("6.0.0.147", release(""))).isEmpty();
	}

	@Test
	void offersNothingWithoutARelease() {
		Assertions.assertThat(UpdateAvailability.decide("6.0.0.147", null)).isEmpty();
	}

	private static PublishedRelease release(String tag) {
		return new PublishedRelease(tag, "https://example.invalid/page", "a.msi", "https://example.invalid/a.msi",
				"https://example.invalid/a.msi.sha256", 1024L);
	}
}