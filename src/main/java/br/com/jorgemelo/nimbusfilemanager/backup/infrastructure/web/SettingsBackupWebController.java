package br.com.jorgemelo.nimbusfilemanager.backup.infrastructure.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * Backup and restore actions of the settings screen. Restoring replaces the
 * whole catalog, so it waits for an idle inventory: a job writing rows into a
 * database being emptied under it would end with neither the old catalog nor
 * the restored one.
 */
@Controller
@Profile(NimbusProfiles.APP)
public class SettingsBackupWebController extends LocalizedComponent {

	private final CatalogBackupService catalogBackupService;
	private final CatalogBackupAsyncRunner asyncRunner;
	private final ExecutionQueryService executionQueryService;

	@Autowired
	public SettingsBackupWebController(CatalogBackupService catalogBackupService, CatalogBackupAsyncRunner asyncRunner,
			ExecutionQueryService executionQueryService) {
		this.catalogBackupService = catalogBackupService;
		this.asyncRunner = asyncRunner;
		this.executionQueryService = executionQueryService;
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

	/**
	 * Refused while <em>any</em> execution is active, and not only while an
	 * inventory is - which is what it asked before, and was both too narrow and
	 * aimed at the wrong thing.
	 *
	 * <p>
	 * A restore runs {@code pg_restore} over the catalog, which drops and recreates
	 * every table - <strong>including {@code execution} itself</strong>. So there is
	 * no such thing as an execution that could safely coexist with it: whatever is
	 * running would be writing progress to a row the restore is in the middle of
	 * replacing with a row from the backup. The exclusion is total because the
	 * table that records the work is part of what is replaced, not because being
	 * careful seemed wise.
	 */
	@PostMapping("/app/settings/backup/restore")
	public String restoreBackup(@RequestParam String name, RedirectAttributes redirectAttributes) {
		if (executionQueryService.active().isPresent()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR,
					message("backend.settings.backupExecutionBlocked"));

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