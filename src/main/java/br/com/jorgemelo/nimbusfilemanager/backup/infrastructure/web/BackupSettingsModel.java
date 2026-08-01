package br.com.jorgemelo.nimbusfilemanager.backup.infrastructure.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import br.com.jorgemelo.nimbusfilemanager.backup.application.BackupFolderResolver;
import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupService;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Read-side assembler for the backup section: where backups are written and
 * which ones exist. Extracted from the settings controller so its render
 * handler keeps a small constructor; the matching actions live in
 * {@link SettingsBackupWebController}.
 */
@Component
public class BackupSettingsModel {

	private final CatalogBackupService catalogBackupService;
	private final BackupFolderResolver backupFolderResolver;

	@Autowired
	public BackupSettingsModel(CatalogBackupService catalogBackupService,
			BackupFolderResolver backupFolderResolver) {
		this.catalogBackupService = catalogBackupService;
		this.backupFolderResolver = backupFolderResolver;
	}

	public void addTo(Model model) {
		model.addAttribute("backups", catalogBackupService.list());
		model.addAttribute("backupFolder", PathUtils.normalize(backupFolderResolver.folder()));
	}
}