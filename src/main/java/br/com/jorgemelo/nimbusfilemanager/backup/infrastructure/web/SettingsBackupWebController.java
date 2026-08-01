package br.com.jorgemelo.nimbusfilemanager.backup.infrastructure.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupService;
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
	private final CatalogBackupAsyncRunner asyncRunner;
	private final InventoryRunningState inventoryRunningState;

	@Autowired
	public SettingsBackupWebController(CatalogBackupService catalogBackupService, CatalogBackupAsyncRunner asyncRunner,
			InventoryRunningState inventoryRunningState) {
		this.catalogBackupService = catalogBackupService;
		this.asyncRunner = asyncRunner;
		this.inventoryRunningState = inventoryRunningState;
	}

	@PostMapping("/app/settings/backup/create")
	public String createBackup(RedirectAttributes redirectAttributes) {
		if (!asyncRunner.start()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR,
					message("backend.settings.backupRunning"));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		asyncRunner.create();

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS,
				message("backend.settings.backupStarted"));

		return SharedConstants.REDIRECT_SETTINGS;
	}

	@PostMapping("/app/settings/backup/restore")
	public String restoreBackup(@RequestParam String name, RedirectAttributes redirectAttributes) {
		if (inventoryRunningState.isRunning()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR,
					message("backend.settings.backupInventoryBlocked"));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		if (!asyncRunner.start()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR,
					message("backend.settings.backupRunning"));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		asyncRunner.restore(name);

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS,
				message("backend.settings.restoreStarted", name));

		return SharedConstants.REDIRECT_SETTINGS;
	}

	/**
	 * Ends a backup in flight. Only a backup: a restore has already dropped
	 * objects to recreate them, so stopping it would leave the catalog neither
	 * as it was nor as it was becoming.
	 */
	@PostMapping("/app/settings/backup/cancel")
	public String cancelBackup(RedirectAttributes redirectAttributes) {
		if (!asyncRunner.cancel()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR,
					message("backend.settings.backupNotCancellable"));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS,
				message("backend.settings.backupCancelled"));

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