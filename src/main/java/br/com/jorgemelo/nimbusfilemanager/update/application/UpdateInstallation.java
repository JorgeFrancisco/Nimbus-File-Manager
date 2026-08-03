package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import br.com.jorgemelo.nimbusfilemanager.update.application.dto.PreparedInstaller;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.PublishedRelease;
import br.com.jorgemelo.nimbusfilemanager.update.domain.enums.UpdateOutcome;

/**
 * Fetches an installer and decides whether it may be run.
 *
 * <p>
 * Kept apart from the service that runs it so the answers that matter can be
 * proven without a network and without executing anything: a download that
 * fails, a checksum that was never published, and - the one that justifies all
 * of this - a file whose bytes do not match what was published.
 *
 * <p>
 * A file that fails verification is deleted rather than kept. Leaving it would
 * mean an installer of unknown provenance sitting in the workspace under the
 * name of a real release, and the next thing to look at that folder - a person,
 * a retry, a cleanup routine that moves files instead of deleting them - would
 * have no way to tell it apart from a good one.
 */
public final class UpdateInstallation {

	private UpdateInstallation() {
	}

	/**
	 * @param release the release to fetch
	 * @param folder where the installer is written, created by the caller
	 * @param downloader how the files are fetched
	 */
	public static PreparedInstaller prepare(PublishedRelease release, Path folder, ReleaseDownloader downloader) {
		Path installer = folder.resolve(release.installerName());

		try {
			downloader.download(release.installerUrl(), installer);
		} catch (IOException _) {
			return refused(UpdateOutcome.DOWNLOAD_FAILED);
		}

		Optional<String> expected = expected(release, downloader);

		if (expected.isEmpty()) {
			delete(installer);

			return refused(UpdateOutcome.CHECKSUM_UNAVAILABLE);
		}

		return verify(installer, expected.get());
	}

	private static PreparedInstaller verify(Path installer, String expected) {
		String actual;

		try {
			actual = Checksums.of(installer);
		} catch (IOException _) {
			delete(installer);

			return refused(UpdateOutcome.DOWNLOAD_FAILED);
		}

		if (!Checksums.matches(actual, expected)) {
			delete(installer);

			return refused(UpdateOutcome.CHECKSUM_MISMATCH);
		}

		return new PreparedInstaller(installer, null);
	}

	private static Optional<String> expected(PublishedRelease release, ReleaseDownloader downloader) {
		try {
			return Checksums.published(downloader.readText(release.checksumUrl()));
		} catch (IOException _) {
			return Optional.empty();
		}
	}

	private static PreparedInstaller refused(UpdateOutcome outcome) {
		return new PreparedInstaller(null, outcome);
	}

	private static void delete(Path installer) {
		try {
			Files.deleteIfExists(installer);
		} catch (IOException _) {
			// Nothing further to do: the file is already refused, and reporting a
			// failure to remove it would replace the reason that matters.
		}
	}
}