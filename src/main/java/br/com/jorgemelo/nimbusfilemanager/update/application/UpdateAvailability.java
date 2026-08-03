package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.util.Optional;

import br.com.jorgemelo.nimbusfilemanager.update.application.dto.ApplicationVersion;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.AvailableUpdate;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.PublishedRelease;

/**
 * The one place that answers whether a published release is worth offering.
 *
 * <p>
 * Separate from the service that fetches it, because every answer worth being
 * sure about is decided here and none of them needs a network: an installation
 * already current, one genuinely behind, a release tagged with something this
 * cannot read, and a run that does not know its own version.
 *
 * <p>
 * That last one is the case that must not be guessed. A run from the IDE has no
 * manifest, so it has no version - and treating that as "very old" would offer
 * an update to every developer running the project from source, over a database
 * they are working on.
 */
public final class UpdateAvailability {

	private UpdateAvailability() {
	}

	/**
	 * @param installedText the version of this run, as the manifest carries it
	 * @param release the most recent published release
	 * @return the update to offer, or empty when there is nothing to offer
	 */
	public static Optional<AvailableUpdate> decide(String installedText, PublishedRelease release) {
		if (release == null) {
			return Optional.empty();
		}

		Optional<ApplicationVersion> installed = ApplicationVersions.parse(installedText);
		Optional<ApplicationVersion> published = ApplicationVersions.parse(release.tag());

		if (installed.isEmpty() || published.isEmpty()) {
			return Optional.empty();
		}

		if (!ApplicationVersions.supersedes(published.get(), installed.get())) {
			return Optional.empty();
		}

		return Optional.of(new AvailableUpdate(installedText.trim(), release.tag(), release));
	}
}