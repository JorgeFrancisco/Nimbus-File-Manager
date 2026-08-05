package br.com.jorgemelo.nimbusfilemanager.backup.infrastructure.web;

import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.backup.application.BackupFolderResolver;
import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.backup.application.RestoreNotice;
import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupService;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web.SettingsSectionModel;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Read-side assembler for the backup section: where backups are written and
 * which ones exist. Extracted from the settings controller so its render
 * handler keeps a small constructor; the matching actions live in
 * {@link SettingsBackupWebController}.
 */
@Component
@Profile(NimbusProfiles.APP)
public class BackupSettingsModel implements SettingsSectionModel {

	private final CatalogBackupService catalogBackupService;
	private final BackupFolderResolver backupFolderResolver;
	private final CatalogBackupAsyncRunner asyncRunner;
	private final RestoreNotice restoreNotice;

	@Autowired
	public BackupSettingsModel(CatalogBackupService catalogBackupService, BackupFolderResolver backupFolderResolver,
			CatalogBackupAsyncRunner asyncRunner, RestoreNotice restoreNotice) {
		this.catalogBackupService = catalogBackupService;
		this.backupFolderResolver = backupFolderResolver;
		this.asyncRunner = asyncRunner;
		this.restoreNotice = restoreNotice;
	}

	@Override
	public void addTo(Model model, Authentication authentication) {
		model.addAttribute("backups", catalogBackupService.list());
		model.addAttribute("backupFolder", PathUtils.normalize(backupFolderResolver.folder()));
		model.addAttribute("backupRunning", asyncRunner.isRunning());
		model.addAttribute("backupProgress", asyncRunner.progress());
		model.addAttribute("backupError", asyncRunner.lastError());
		model.addAttribute("backupResult", asyncRunner.lastResult());
		model.addAttribute("catalogRestored", restoreNotice.consume().orElse(null));
	}
}