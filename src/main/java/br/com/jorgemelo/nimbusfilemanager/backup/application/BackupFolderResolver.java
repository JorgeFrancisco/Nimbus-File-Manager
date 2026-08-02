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

	/**
	 * Where a backup is built before it is kept.
	 *
	 * <p>
	 * Always inside the workspace, even when the backup itself goes elsewhere. A
	 * file written straight into its final folder grows there for minutes, and
	 * everything watching that folder reacts to every batch of bytes: the
	 * inventory watcher, a cloud client synchronising the drive, an antivirus
	 * scanning as it goes. Building it here and moving the finished file means
	 * they all see one complete file appear instead.
	 */
	public Path staging() {
		Path folder = workspaceManager.resolve(WorkspaceFolders.TEMP);

		try {
			Files.createDirectories(folder);
		} catch (IOException e) {
			throw new IllegalStateException("Could not create the staging folder " + folder, e);
		}

		return folder;
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