package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.update.application.dto.PreparedInstaller;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.PublishedRelease;
import br.com.jorgemelo.nimbusfilemanager.update.domain.enums.UpdateOutcome;

/**
 * The last gate before a file downloaded over the network is executed with
 * installer privileges. Every refusal here has to leave nothing behind that a
 * later step could mistake for a verified installer.
 */
class UpdateInstallationTest {

	private static final byte[] CONTENT = "an installer".getBytes();

	@Test
	void deliversAnInstallerWhoseBytesMatchWhatWasPublished(@TempDir Path folder) throws IOException {
		String hash = hashOf(folder);

		PreparedInstaller prepared = UpdateInstallation.prepare(release(), folder,
				downloader(CONTENT, hash + "  a.msi"));

		Assertions.assertThat(prepared.refusal()).isNull();
		Assertions.assertThat(prepared.installer()).exists().hasBinaryContent(CONTENT);
	}

	@Test
	void refusesWhenTheDownloadFails(@TempDir Path folder) {
		PreparedInstaller prepared = UpdateInstallation.prepare(release(), folder, failingDownload());

		Assertions.assertThat(prepared.refusal()).isEqualTo(UpdateOutcome.DOWNLOAD_FAILED);
		Assertions.assertThat(prepared.installer()).isNull();
	}

	/**
	 * A release with no readable checksum is not installed at all. Running it
	 * anyway would make the verification a formality that disappears exactly when
	 * something is wrong.
	 */
	@Test
	void refusesAndDeletesWhenNoChecksumWasPublished(@TempDir Path folder) {
		PreparedInstaller prepared = UpdateInstallation.prepare(release(), folder, downloader(CONTENT, "<html>404"));

		Assertions.assertThat(prepared.refusal()).isEqualTo(UpdateOutcome.CHECKSUM_UNAVAILABLE);
		Assertions.assertThat(folder.resolve("a.msi")).doesNotExist();
	}

	@Test
	void refusesAndDeletesWhenTheChecksumCannotBeFetched(@TempDir Path folder) {
		PreparedInstaller prepared = UpdateInstallation.prepare(release(), folder, failingChecksum());

		Assertions.assertThat(prepared.refusal()).isEqualTo(UpdateOutcome.CHECKSUM_UNAVAILABLE);
		Assertions.assertThat(folder.resolve("a.msi")).doesNotExist();
	}

	/**
	 * The case the whole step exists for: what arrived is not what was published,
	 * whether truncated in transit or replaced along the way.
	 */
	@Test
	void refusesAndDeletesWhatDoesNotMatchThePublishedHash(@TempDir Path folder) {
		PreparedInstaller prepared = UpdateInstallation.prepare(release(), folder,
				downloader(CONTENT, "0".repeat(64) + "  a.msi"));

		Assertions.assertThat(prepared.refusal()).isEqualTo(UpdateOutcome.CHECKSUM_MISMATCH);
		Assertions.assertThat(folder.resolve("a.msi")).doesNotExist();
	}

	private static String hashOf(Path folder) throws IOException {
		Path sample = folder.resolve("sample");

		Files.write(sample, CONTENT);

		String hash = Checksums.of(sample);

		Files.delete(sample);

		return hash;
	}

	private static PublishedRelease release() {
		return new PublishedRelease("v6.1.0.160", "page", "a.msi", "https://example.invalid/a.msi",
				"https://example.invalid/a.msi.sha256", CONTENT.length);
	}

	private static ReleaseDownloader downloader(byte[] content, String checksum) {
		return new ReleaseDownloader() {

			@Override
			public void download(String url, Path target) throws IOException {
				Files.write(target, content);
			}

			@Override
			public String readText(String url) {
				return checksum;
			}
		};
	}

	private static ReleaseDownloader failingDownload() {
		return new ReleaseDownloader() {

			@Override
			public void download(String url, Path target) throws IOException {
				throw new IOException("refused");
			}

			@Override
			public String readText(String url) {
				return "";
			}
		};
	}

	private static ReleaseDownloader failingChecksum() {
		return new ReleaseDownloader() {

			@Override
			public void download(String url, Path target) throws IOException {
				Files.write(target, CONTENT);
			}

			@Override
			public String readText(String url) throws IOException {
				throw new IOException("refused");
			}
		};
	}
}