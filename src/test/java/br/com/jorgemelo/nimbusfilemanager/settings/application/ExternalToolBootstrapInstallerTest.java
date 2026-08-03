package br.com.jorgemelo.nimbusfilemanager.settings.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.ExternalToolStatus;

/**
 * Every start installs what this installation does not have of its own, and
 * stays out of the way whenever the answer is already settled.
 */
class ExternalToolBootstrapInstallerTest {

	private final ExternalToolInstaller installer = mock(ExternalToolInstaller.class);
	private final ExternalToolInstallAsyncRunner runner = mock(ExternalToolInstallAsyncRunner.class);

	private final ExternalToolBootstrapInstaller bootstrap = new ExternalToolBootstrapInstaller(installer, runner);

	private ExternalToolStatus status(boolean available, boolean bundled, boolean installable) {
		return new ExternalToolStatus(available, "ffmpeg", available, "ffprobe", null, bundled, installable,
				"C:/app/tools/ffmpeg/bin");
	}

	@Test
	void downloadsTheToolsOnAStartThatDoesNotFindThem() {
		when(installer.status()).thenReturn(status(false, false, true));
		when(runner.start()).thenReturn(true);

		bootstrap.installWhenMissing();

		verify(runner).install();
	}

	/**
	 * The behaviour this component exists for: a machine that already answers
	 * {@code ffmpeg} on PATH used to end the story, leaving the installation on a
	 * build of unknown provenance and age. PATH is what keeps the features working
	 * until the download lands, not a reason to skip it.
	 */
	@Test
	void downloadsEvenWhenSomethingOnThePathAlreadyAnswers() {
		when(installer.status()).thenReturn(status(true, false, true));
		when(runner.start()).thenReturn(true);

		bootstrap.installWhenMissing();

		verify(runner).install();
	}

	/**
	 * Our own copy is a file to overwrite, and the startup inventory may be
	 * executing it - which Windows refuses. Updating it is the settings button's
	 * job, since that one waits for an idle inventory.
	 */
	@Test
	void staysQuietWhenTheDownloadedCopyIsAlreadyHere() {
		when(installer.status()).thenReturn(status(true, true, true));

		bootstrap.installWhenMissing();

		verify(runner, never()).start();
		verify(runner, never()).install();
	}

	/**
	 * On Linux and macOS the package manager owns the install, so a missing tool is
	 * not this component's business.
	 */
	@Test
	void staysQuietOnAPlatformItCannotInstallOn() {
		when(installer.status()).thenReturn(status(false, false, false));

		bootstrap.installWhenMissing();

		verify(runner, never()).install();
	}

	/** A manual install already in flight owns the folder; do not race it. */
	@Test
	void staysQuietWhenAnInstallationIsAlreadyRunning() {
		when(installer.status()).thenReturn(status(false, false, true));
		when(runner.start()).thenReturn(false);

		bootstrap.installWhenMissing();

		verify(runner, never()).install();
	}
}