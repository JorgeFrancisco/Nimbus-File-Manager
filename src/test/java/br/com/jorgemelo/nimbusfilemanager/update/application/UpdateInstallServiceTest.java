package br.com.jorgemelo.nimbusfilemanager.update.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.shared.application.ApplicationShutdown;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.AvailableUpdate;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.PublishedRelease;
import br.com.jorgemelo.nimbusfilemanager.update.domain.enums.UpdateOutcome;
import br.com.jorgemelo.nimbusfilemanager.update.infrastructure.UpdateInstallProcessRunner;

/**
 * Installing means running a downloaded file with installer privileges and then
 * ending the run. Both halves have to be provable: that nothing is started
 * unless it was verified, and that the run only ends once something was.
 */
class UpdateInstallServiceTest {

	private static final byte[] CONTENT = "an installer".getBytes();

	private final UpdateCheckService updateCheckService = mock(UpdateCheckService.class);
	private final UpdateInstallProcessRunner processRunner = mock(UpdateInstallProcessRunner.class);
	private final WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
	private final ApplicationShutdown applicationShutdown = mock(ApplicationShutdown.class);
	private final UpdateInstallProgress progress = new UpdateInstallProgress();

	@Test
	void refusesWhenNothingWasFound() throws IOException {
		when(updateCheckService.available()).thenReturn(Optional.empty());

		UpdateOutcome outcome = service(downloader("")).install();

		Assertions.assertThat(outcome).isEqualTo(UpdateOutcome.NOTHING_TO_INSTALL);

		verify(processRunner, never()).start(any(), any(), any());
		verify(applicationShutdown, never()).endRun();
	}

	/**
	 * The published artefact is an MSI. Elsewhere there is nothing to install even
	 * when a newer version exists, and saying so beats downloading a file that
	 * cannot run.
	 */
	@Test
	void refusesOnAPlatformThatCannotRunTheInstaller(@TempDir Path folder) throws IOException {
		when(updateCheckService.available()).thenReturn(Optional.of(update()));

		UpdateOutcome outcome = elsewhere(folder, downloader(hashOf(folder) + "  a.msi")).install();

		Assertions.assertThat(outcome).isEqualTo(UpdateOutcome.UNSUPPORTED_PLATFORM);

		verify(processRunner, never()).start(any(), any(), any());
	}

	@Test
	void startsTheInstallerAndEndsTheRunWhenTheBytesMatch(@TempDir Path folder) throws IOException {
		when(updateCheckService.available()).thenReturn(Optional.of(update()));
		when(workspaceManager.resolve(any())).thenReturn(folder);

		UpdateOutcome outcome = onWindows(downloader(hashOf(folder) + "  a.msi")).install();

		Assertions.assertThat(outcome).isEqualTo(UpdateOutcome.STARTED);

		verify(processRunner).start(eq(folder.resolve("a.msi")), any(), eq("6.1.0"));
		verify(applicationShutdown).endRun();
	}

	/**
	 * The case the verification exists for. Nothing is started, and - the part
	 * worth asserting - this run keeps going, so the person is still there to be
	 * told why.
	 */
	@Test
	void neitherStartsNorEndsTheRunWhenTheBytesDoNotMatch(@TempDir Path folder) throws IOException {
		when(updateCheckService.available()).thenReturn(Optional.of(update()));
		when(workspaceManager.resolve(any())).thenReturn(folder);

		UpdateOutcome outcome = onWindows(downloader("0".repeat(64) + "  a.msi")).install();

		Assertions.assertThat(outcome).isEqualTo(UpdateOutcome.CHECKSUM_MISMATCH);

		verify(processRunner, never()).start(any(), any(), any());
		verify(applicationShutdown, never()).endRun();
	}

	@Test
	void reportsAnInstallerThatCouldNotBeStarted(@TempDir Path folder) throws IOException {
		when(updateCheckService.available()).thenReturn(Optional.of(update()));
		when(workspaceManager.resolve(any())).thenReturn(folder);

		doThrow(new IOException("denied")).when(processRunner).start(any(), any(), any());

		UpdateOutcome outcome = onWindows(downloader(hashOf(folder) + "  a.msi")).install();

		Assertions.assertThat(outcome).isEqualTo(UpdateOutcome.COULD_NOT_START);

		verify(applicationShutdown, never()).endRun();
	}

	@Test
	void offersTheActionOnlyWhenThereIsSomethingToInstall() {
		when(updateCheckService.available()).thenReturn(Optional.empty());

		Assertions.assertThat(onWindows(downloader("")).canInstall()).isFalse();

		when(updateCheckService.available()).thenReturn(Optional.of(update()));

		Assertions.assertThat(onWindows(downloader("")).canInstall()).isTrue();
		Assertions.assertThat(elsewhere(null, downloader("")).canInstall()).isFalse();
	}

	@Test
	void reportsAFolderThatCouldNotBePrepared(@TempDir Path folder) throws IOException {
		Path file = Files.write(folder.resolve("in-the-way"), CONTENT);

		when(updateCheckService.available()).thenReturn(Optional.of(update()));
		when(workspaceManager.resolve(any())).thenReturn(file.resolve("temp"));

		Assertions.assertThat(onWindows(downloader("")).install()).isEqualTo(UpdateOutcome.DOWNLOAD_FAILED);
	}

	/**
	 * The real platform check, which every other case here overrides. Stated per
	 * platform rather than restated as an expression, so it survives on the CI
	 * that runs Linux as well as on the machine that builds the installer.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void recognizesTheOnlyPlatformThatRunsTheInstaller() {
		Assertions.assertThat(service(downloader("")).installable()).isTrue();
	}

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void recognizesAPlatformThatCannotRunTheInstaller() {
		Assertions.assertThat(service(downloader("")).installable()).isFalse();
	}

	private UpdateInstallService service(ReleaseDownloader downloader) {
		return new UpdateInstallService(updateCheckService, downloader, processRunner, workspaceManager,
				applicationShutdown, progress);
	}

	/** Answers as a Windows run would, whatever this machine is. */
	private UpdateInstallService onWindows(ReleaseDownloader downloader) {
		return new UpdateInstallService(updateCheckService, downloader, processRunner, workspaceManager,
				applicationShutdown, progress) {

			@Override
			boolean installable() {
				return true;
			}
		};
	}

	private UpdateInstallService elsewhere(Path folder, ReleaseDownloader downloader) {
		if (folder != null) {
			when(workspaceManager.resolve(any())).thenReturn(folder);
		}

		return new UpdateInstallService(updateCheckService, downloader, processRunner, workspaceManager,
				applicationShutdown, progress) {

			@Override
			boolean installable() {
				return false;
			}
		};
	}

	private static AvailableUpdate update() {
		return new AvailableUpdate("6.0.0.147", "v6.1.0.160", new PublishedRelease("v6.1.0.160", "page", "a.msi",
				"https://example.invalid/a.msi", "https://example.invalid/a.msi.sha256", CONTENT.length));
	}

	private static String hashOf(Path folder) throws IOException {
		Path sample = folder.resolve("sample");

		Files.write(sample, CONTENT);

		String hash = Checksums.of(sample);

		Files.delete(sample);

		return hash;
	}

	private static ReleaseDownloader downloader(String checksum) {
		return new ReleaseDownloader() {

			@Override
			public void download(String url, Path target) throws IOException {
				Files.write(target, CONTENT);
			}

			@Override
			public String readText(String url) {
				return checksum;
			}
		};
	}
}