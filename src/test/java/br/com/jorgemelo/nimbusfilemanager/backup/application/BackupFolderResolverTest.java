package br.com.jorgemelo.nimbusfilemanager.backup.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceFolders;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;

/**
 * Where backups land. The default keeps them inside the workspace the
 * application already owns; the setting exists because the one disk a backup
 * should not sit on is the one it is protecting.
 */
class BackupFolderResolverTest {

	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final WorkspaceManager workspaceManager = mock(WorkspaceManager.class);

	private final BackupFolderResolver resolver = new BackupFolderResolver(appSettingService, workspaceManager);

	private void configured(String folder) {
		when(appSettingService.stringValue(SettingsConstants.BACKUP_FOLDER, "")).thenReturn(folder);
	}

	@Test
	void fallsBackToTheWorkspaceFolderWhenNothingIsConfigured(@TempDir Path workspace) {
		configured("");

		when(workspaceManager.resolve(WorkspaceFolders.BACKUP)).thenReturn(workspace.resolve("backup"));

		Assertions.assertThat(resolver.folder()).isEqualTo(workspace.resolve("backup")).exists();
	}

	/** A folder chosen on screen wins, and is created if it is not there yet. */
	@Test
	void usesTheConfiguredFolderAndCreatesIt(@TempDir Path elsewhere) {
		Path chosen = elsewhere.resolve("external-drive").resolve("nimbus");

		configured(chosen.toString());

		Assertions.assertThat(resolver.folder()).isEqualTo(chosen).exists();

		// The workspace is never consulted once a folder was chosen.
		verify(workspaceManager, never()).resolve(any());
	}

	/**
	 * A folder that cannot be created - a drive that is gone, a name taken by a
	 * file - has to say so at the moment of the backup, not fail silently and leave
	 * the operator believing they are protected.
	 */
	@Test
	void failsLoudlyWhenTheFolderCannotBeCreated(@TempDir Path workspace) throws IOException {
		Path takenByAFile = workspace.resolve("occupied");

		Files.writeString(takenByAFile, "not a folder");

		configured(takenByAFile.resolve("backup").toString());

		Assertions.assertThatIllegalStateException().isThrownBy(resolver::folder)
				.withMessageContaining("Could not create the backup folder");
	}
}