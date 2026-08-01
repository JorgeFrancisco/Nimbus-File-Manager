package br.com.jorgemelo.nimbusfilemanager.settings.application;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.ExternalToolStatus;

/**
 * The first start installs what is missing, and stays out of the way whenever
 * the answer is already settled.
 */
class ExternalToolBootstrapInstallerTest {

	private final ExternalToolInstaller installer = mock(ExternalToolInstaller.class);
	private final ExternalToolInstallAsyncRunner runner = mock(ExternalToolInstallAsyncRunner.class);
	private final AppSettingService appSettingService = mock(AppSettingService.class);

	private final ExternalToolBootstrapInstaller bootstrap = new ExternalToolBootstrapInstaller(installer, runner,
			appSettingService);

	@BeforeEach
	void enableTheSetting() {
		when(appSettingService.booleanValue(eq(SettingsConstants.TOOL_AUTO_INSTALL), anyBoolean())).thenReturn(true);
	}

	private ExternalToolStatus status(boolean ffmpeg, boolean ffprobe, boolean installable) {
		return new ExternalToolStatus(ffmpeg, "ffmpeg", ffprobe, "ffprobe", null, false, installable,
				"C:/app/tools/bin");
	}

	@Test
	void downloadsTheToolsOnAStartThatDoesNotFindThem() {
		when(installer.status()).thenReturn(status(false, false, true));
		when(runner.start()).thenReturn(true);

		bootstrap.installWhenMissing();

		verify(runner).install();
	}

	/**
	 * A half-installed folder has a file to overwrite, and the startup inventory
	 * may be executing it - which Windows refuses. Finishing it is the settings
	 * button's job, since that one waits for an idle inventory.
	 */
	@Test
	void staysQuietWhenOneOfTheTwoIsAlreadyInPlace() {
		when(installer.status()).thenReturn(status(false, true, true));

		bootstrap.installWhenMissing();

		verify(runner, never()).start();
		verify(runner, never()).install();
	}

	@Test
	void staysQuietWhenBothToolsAlreadyRun() {
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

	/** An operator who turned it off is not asked again on every restart. */
	@Test
	void staysQuietWhenTheSettingIsOff() {
		when(appSettingService.booleanValue(eq(SettingsConstants.TOOL_AUTO_INSTALL), anyBoolean())).thenReturn(false);

		bootstrap.installWhenMissing();

		verify(installer, never()).status();
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