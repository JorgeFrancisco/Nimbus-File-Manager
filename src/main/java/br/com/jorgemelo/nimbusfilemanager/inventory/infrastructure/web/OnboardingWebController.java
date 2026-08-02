package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.web;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.com.jorgemelo.nimbusfilemanager.backup.application.BackupFolderResolver;
import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupService;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.batch.InventoryBatchLauncherService;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryRequest;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.InventoryWatchService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * First-run wizard: asks which folder to watch (plus the same options the old
 * manual "Inventario" screen exposed) once, persists the choice as app
 * settings, and kicks off the first scan. DashboardWebController redirects here
 * whenever {@link AppSettingService#WATCH_FOLDER} is still unconfigured, so
 * every fresh install goes through this before seeing the dashboard.
 */
@Controller
public class OnboardingWebController extends LocalizedComponent {

	private final AppSettingService appSettingService;
	private final InventoryBatchLauncherService inventoryBatchLauncherService;
	private final InventoryWatchService inventoryWatchService;
	private final CatalogBackupService catalogBackupService;
	private final CatalogBackupAsyncRunner backupRunner;
	private final BackupFolderResolver backupFolderResolver;

	@Autowired
	public OnboardingWebController(AppSettingService appSettingService,
			InventoryBatchLauncherService inventoryBatchLauncherService, InventoryWatchService inventoryWatchService,
			CatalogBackupService catalogBackupService, CatalogBackupAsyncRunner backupRunner,
			BackupFolderResolver backupFolderResolver) {
		this.appSettingService = appSettingService;
		this.inventoryBatchLauncherService = inventoryBatchLauncherService;
		this.inventoryWatchService = inventoryWatchService;
		this.catalogBackupService = catalogBackupService;
		this.backupRunner = backupRunner;
		this.backupFolderResolver = backupFolderResolver;
	}

	/**
	 * Two ways in, because a fresh installation is not always a fresh library.
	 * Someone arriving with a backup wants their catalog back, not a first scan of
	 * a hundred thousand files - and the folder to watch is inside the backup, so
	 * restoring is what configures the installation.
	 */
	@GetMapping("/app/onboarding")
	public String onboarding(Model model) {
		if (isConfigured()) {
			return "redirect:/app";
		}

		model.addAttribute("backups", catalogBackupService.list());
		model.addAttribute("backupFolder", backupFolderResolver.folder().toString());
		model.addAttribute("restoring", backupRunner.isRunning());
		model.addAttribute("restoreError", backupRunner.lastError());

		return "app/onboarding";
	}

	/**
	 * Points the restore panel at the folder where the backup actually is, and
	 * keeps the choice - it is the same setting the settings screen edits, so an
	 * installation restored from an external drive goes on writing its backups
	 * there instead of forgetting where they live.
	 */
	@PostMapping("/app/onboarding/backup-folder")
	public String chooseBackupFolder(@RequestParam String backupPath, Authentication authentication,
			RedirectAttributes redirectAttributes) {
		String validationError = validateFolder(backupPath, "backend.onboarding.backupFolderRequired");

		if (validationError != null) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR, validationError);

			return "redirect:/app/onboarding";
		}

		String username = authentication != null ? authentication.getName() : null;

		appSettingService.update(SettingsConstants.BACKUP_FOLDER, backupPath.trim(), username);

		return "redirect:/app/onboarding";
	}

	@PostMapping("/app/onboarding")
	public String configure(@RequestParam String sourcePath, @RequestParam(defaultValue = "false") boolean recursive,
			@RequestParam(defaultValue = "false") boolean includeHidden,
			@RequestParam(defaultValue = "false") boolean calculateHashes,
			@RequestParam(defaultValue = "false") boolean forceAnalysis, Authentication authentication, Model model) {
		model.addAttribute("sourcePath", sourcePath);
		model.addAttribute("recursive", recursive);
		model.addAttribute("includeHidden", includeHidden);
		model.addAttribute("calculateHashes", calculateHashes);
		model.addAttribute("forceAnalysis", forceAnalysis);

		String validationError = validateSourcePath(sourcePath);

		if (validationError != null) {
			model.addAttribute("error", validationError);

			return "app/onboarding";
		}

		String username = authentication != null ? authentication.getName() : null;

		appSettingService.update(SettingsConstants.WATCH_RECURSIVE, Boolean.toString(recursive), username);
		appSettingService.update(SettingsConstants.WATCH_INCLUDE_HIDDEN, Boolean.toString(includeHidden), username);
		appSettingService.update(SettingsConstants.WATCH_CALCULATE_HASHES, Boolean.toString(calculateHashes), username);
		appSettingService.update(SettingsConstants.WATCH_FORCE_ANALYSIS, Boolean.toString(forceAnalysis), username);

		// Written last: DashboardWebController treats a non-blank WATCH_FOLDER as
		// "onboarding done",
		// so every other setting above must already be saved by the time this one
		// lands.
		appSettingService.update(SettingsConstants.WATCH_FOLDER, sourcePath, username);

		inventoryWatchService.reconfigure();

		var request = new InventoryRequest(sourcePath, recursive, includeHidden, calculateHashes, forceAnalysis);
		var started = inventoryBatchLauncherService.launch(request, ExecutionTrigger.MANUAL);

		return "redirect:/app/progress/" + started.executionId() + "?kind=inventory";
	}

	private boolean isConfigured() {
		return !appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "").isBlank();
	}

	private String validateSourcePath(String sourcePath) {
		return validateFolder(sourcePath, "backend.onboarding.folderRequired");
	}

	/** Same check for both folders; only what to say when it is missing differs. */
	private String validateFolder(String folder, String requiredKey) {
		if (folder == null || folder.isBlank()) {
			return message(requiredKey);
		}

		Path path = Path.of(folder).toAbsolutePath().normalize();

		if (!Files.isDirectory(path)) {
			return message("backend.onboarding.folderInvalid", path);
		}

		return null;
	}
}