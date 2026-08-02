package br.com.jorgemelo.nimbusfilemanager.settings.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.CatalogRestored;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;

/**
 * What happens to the two settings that describe the machine rather than the
 * catalog, when they stop describing it.
 *
 * <p>
 * The paths themselves are already safe - a saved path whose file is gone loses
 * to discovery. What this fixes is the screen saying two different things about
 * the same tool, and it has to do that without overruling a path somebody chose
 * on purpose, and without writing down a guess.
 */
class ExternalToolPathRefreshTest {

	private static final String BACKUP = "nimbus-catalog-20260801-060000.zip";
	private static final String STALE = "./tools/bin/ffmpeg.exe";

	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final ExternalToolPaths externalToolPaths = mock(ExternalToolPaths.class);

	private final ExternalToolPathRefresh refresh = new ExternalToolPathRefresh(appSettingService, externalToolPaths);

	/** The path a backup brought in, naming a folder on another machine. */
	@Test
	void rewritesAPathThatCameFromTheInstallationTheBackupWasTakenOn(@TempDir Path tools) throws IOException {
		String here = installed(tools, "ffmpeg.exe");

		when(appSettingService.stringValue(SettingsConstants.TOOL_FFMPEG, null)).thenReturn(STALE);
		when(externalToolPaths.ffmpeg()).thenReturn(here);

		refresh.onCatalogRestored(new CatalogRestored(BACKUP));

		verify(appSettingService).update(SettingsConstants.TOOL_FFMPEG, here, "system");
	}

	/**
	 * A restore is not the only way to get here. This installation renamed the
	 * folder its tools live in, and a catalog that was never restored anywhere kept
	 * the previous path - waiting for a restore to correct it would be waiting for
	 * something most installations never do.
	 */
	@Test
	void rewritesAPathLeftBehindByAnOlderLayoutAtStartUp(@TempDir Path tools) throws IOException {
		String here = installed(tools, "ffprobe.exe");

		when(appSettingService.stringValue(SettingsConstants.TOOL_FFPROBE, null)).thenReturn(STALE);
		when(externalToolPaths.ffprobe()).thenReturn(here);

		refresh.onApplicationReady();

		verify(appSettingService).update(SettingsConstants.TOOL_FFPROBE, here, "system");
	}

	/**
	 * At start-up this runs beside the bootstrap installer, on the same event and
	 * in no defined order, so it can be asked before ffmpeg has been downloaded.
	 * Resolution answers with the bare command then, and pinning that would swap a
	 * wrong path for a PATH lookup that fails on every Windows machine without one.
	 */
	@Test
	void writesNothingWhileThereIsNoBinaryToPointAt() {
		when(appSettingService.stringValue(any(), any())).thenReturn(STALE);
		when(externalToolPaths.ffmpeg()).thenReturn("ffmpeg");
		when(externalToolPaths.ffprobe()).thenReturn("ffprobe");

		refresh.onApplicationReady();

		verify(appSettingService, never()).update(any(), any(), any());
	}

	/**
	 * A path that still resolves to itself is somebody pointing the installation at
	 * a tool on purpose - a system ffmpeg, a build of their own. Neither a restore
	 * nor a restart is a reason to take that away.
	 */
	@Test
	void leavesAPathThatStillPointsWhereItSaysAlone(@TempDir Path tools) throws IOException {
		String chosen = installed(tools, "ffmpeg.exe");

		when(appSettingService.stringValue(any(), any())).thenReturn(chosen);
		when(externalToolPaths.ffmpeg()).thenReturn(chosen);
		when(externalToolPaths.ffprobe()).thenReturn(chosen);

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

		refresh.onApplicationReady();

		verify(appSettingService, never()).update(any(), any(), any());
	}

	/** Both tools travel together and go stale together, so both are realigned. */
	@Test
	void realignsFfprobeAsWellAsFfmpeg(@TempDir Path tools) throws IOException {
		String ffmpeg = installed(tools, "ffmpeg.exe");
		String ffprobe = installed(tools, "ffprobe.exe");

		when(appSettingService.stringValue(any(), any())).thenReturn(STALE);
		when(externalToolPaths.ffmpeg()).thenReturn(ffmpeg);
		when(externalToolPaths.ffprobe()).thenReturn(ffprobe);

		refresh.onCatalogRestored(new CatalogRestored(BACKUP));

		verify(appSettingService).update(SettingsConstants.TOOL_FFMPEG, ffmpeg, "system");
		verify(appSettingService).update(SettingsConstants.TOOL_FFPROBE, ffprobe, "system");
	}

	private String installed(Path tools, String name) throws IOException {
		return Files.createFile(tools.resolve(name)).toString();
	}
}