package br.com.jorgemelo.nimbusfilemanager.backup.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceFolders;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;

/**
 * Where backups are written. Inside the workspace by default, because that is
 * the folder the application already owns and creates; configurable because the
 * one place a backup should not live is the disk it is protecting against - an
 * external drive or a network share is the whole point of taking one.
 */
@Component
public class BackupFolderResolver {

	private final AppSettingService appSettingService;
	private final WorkspaceManager workspaceManager;

	public BackupFolderResolver(AppSettingService appSettingService, WorkspaceManager workspaceManager) {
		this.appSettingService = appSettingService;
		this.workspaceManager = workspaceManager;
	}

	/** The configured folder, created if needed, or the workspace default. */
	public Path folder() {
		String configured = appSettingService.stringValue(SettingsConstants.BACKUP_FOLDER, "");

		Path folder = configured.isBlank() ? workspaceManager.resolve(WorkspaceFolders.BACKUP)
				: Path.of(configured.trim());

		try {
			Files.createDirectories(folder);
		} catch (IOException e) {
			throw new IllegalStateException("Could not create the backup folder " + folder, e);
		}

		return folder;
	}
}