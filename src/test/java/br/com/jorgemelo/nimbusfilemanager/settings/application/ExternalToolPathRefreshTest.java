package br.com.jorgemelo.nimbusfilemanager.settings.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.CatalogRestored;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;

/**
 * What a restore leaves behind in the two settings that describe the machine
 * rather than the catalog.
 *
 * <p>
 * The paths themselves are already safe - a saved path whose file is gone loses
 * to discovery. What this fixes is the screen saying two different things about
 * the same tool, and it has to fix that without overruling a path somebody
 * chose on purpose.
 */
class ExternalToolPathRefreshTest {

	private static final String BACKUP = "nimbus-catalog-20260801-060000.zip";
	private static final String HERE = "C:/Users/jorge/Nimbus File Manager/workspace/tools/ffmpeg/bin/ffmpeg.exe";

	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final ExternalToolPaths externalToolPaths = mock(ExternalToolPaths.class);

	private final ExternalToolPathRefresh refresh = new ExternalToolPathRefresh(appSettingService, externalToolPaths);

	/** The path the backup brought in names a folder on another machine. */
	@Test
	void rewritesAPathThatCameFromTheInstallationTheBackupWasTakenOn() {
		when(appSettingService.stringValue(SettingsConstants.TOOL_FFMPEG, null)).thenReturn("./tools/bin/ffmpeg.exe");
		when(externalToolPaths.ffmpeg()).thenReturn(HERE);

		refresh.onCatalogRestored(new CatalogRestored(BACKUP));

		verify(appSettingService).update(SettingsConstants.TOOL_FFMPEG, HERE, "system");
	}

	/**
	 * A path that still resolves to itself is somebody pointing the installation at
	 * a tool on purpose - a system ffmpeg, a build of their own. Restoring a
	 * catalog is no reason to take that away.
	 */
	@Test
	void leavesAPathThatStillPointsWhereItSaysAlone() {
		when(appSettingService.stringValue(any(), any())).thenReturn("D:/ffmpeg/bin/ffmpeg.exe");
		when(externalToolPaths.ffmpeg()).thenReturn("D:/ffmpeg/bin/ffmpeg.exe");
		when(externalToolPaths.ffprobe()).thenReturn("D:/ffmpeg/bin/ffmpeg.exe");

		refresh.onCatalogRestored(new CatalogRestored(BACKUP));

		verify(appSettingService, never()).update(any(), any(), any());
	}

	/**
	 * An unset path is the setting meaning "find it for me". Writing the discovered
	 * path into it would answer a question nobody asked, and would pin to today's
	 * folder a choice that was deliberately left open.
	 */
	@Test
	void leavesAnUnsetPathUnset() {
		when(appSettingService.stringValue(any(), any())).thenReturn(null);

		refresh.onCatalogRestored(new CatalogRestored(BACKUP));

		verify(appSettingService, never()).update(any(), any(), any());
	}

	/** Both tools travel in the same backup, so both are realigned. */
	@Test
	void realignsFfprobeAsWellAsFfmpeg() {
		when(appSettingService.stringValue(any(), any())).thenReturn("./tools/bin/anything.exe");
		when(externalToolPaths.ffmpeg()).thenReturn(HERE);
		when(externalToolPaths.ffprobe()).thenReturn("ffprobe");

		refresh.onCatalogRestored(new CatalogRestored(BACKUP));

		verify(appSettingService).update(SettingsConstants.TOOL_FFMPEG, HERE, "system");
		verify(appSettingService).update(SettingsConstants.TOOL_FFPROBE, "ffprobe", "system");
	}
}