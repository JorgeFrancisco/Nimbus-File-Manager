package br.com.jorgemelo.nimbusfilemanager.backup.infrastructure.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupService;
import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.BackupFile;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * Backup and restore actions of the settings screen. Restoring replaces the
 * whole catalog, so it waits for an idle inventory: a job writing rows into a
 * database being emptied under it would end with neither the old catalog nor
 * the restored one.
 */
@Controller
public class SettingsBackupWebController extends LocalizedComponent {

	private final CatalogBackupService catalogBackupService;
	private final InventoryRunningState inventoryRunningState;

	@Autowired
	public SettingsBackupWebController(CatalogBackupService catalogBackupService,
			InventoryRunningState inventoryRunningState) {
		this.catalogBackupService = catalogBackupService;
		this.inventoryRunningState = inventoryRunningState;
	}

	@PostMapping("/app/settings/backup/create")
	public String createBackup(RedirectAttributes redirectAttributes) {
		try {
			BackupFile backup = catalogBackupService.create();

			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS,
					message("backend.settings.backupCreated", backup.name()));
		} catch (RuntimeException e) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR,
					message("backend.settings.backupFailed", e.getMessage()));
		}

		return SharedConstants.REDIRECT_SETTINGS;
	}

	@PostMapping("/app/settings/backup/restore")
	public String restoreBackup(@RequestParam String name, RedirectAttributes redirectAttributes) {
		if (inventoryRunningState.isRunning()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR,
					message("backend.settings.backupInventoryBlocked"));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		try {
			catalogBackupService.restore(name);

			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS,
					message("backend.settings.backupRestored", name));
		} catch (RuntimeException e) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR,
					message("backend.settings.backupRestoreFailed", e.getMessage()));
		}

		return SharedConstants.REDIRECT_SETTINGS;
	}

	@PostMapping("/app/settings/backup/delete")
	public String deleteBackup(@RequestParam String name, RedirectAttributes redirectAttributes) {
		try {
			catalogBackupService.delete(name);

			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS,
					message("backend.settings.backupDeleted", name));
		} catch (RuntimeException e) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR,
					message("backend.settings.backupFailed", e.getMessage()));
		}

		return SharedConstants.REDIRECT_SETTINGS;
	}
}