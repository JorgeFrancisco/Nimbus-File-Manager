package br.com.jorgemelo.nimbusfilemanager.settings.application;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.InventoryWatchService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.AsyncConfig;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class LibrarySwitchService extends LocalizedComponent {

	private static final long CANCELLATION_POLL_MILLIS = 200;
	private static final long CANCELLATION_TIMEOUT_MILLIS = 120_000;

	private final AppSettingService appSettingService;
	private final InventoryWatchService inventoryWatchService;
	private final ExecutionQueryService executionQueryService;
	private final ExecutionCancellationService cancellationService;
	private final LibraryCatalogCleanupService cleanupService;

	public LibrarySwitchService(AppSettingService appSettingService, InventoryWatchService inventoryWatchService,
			ExecutionQueryService executionQueryService, ExecutionCancellationService cancellationService,
			LibraryCatalogCleanupService cleanupService) {
		this.appSettingService = appSettingService;
		this.inventoryWatchService = inventoryWatchService;
		this.executionQueryService = executionQueryService;
		this.cancellationService = cancellationService;
		this.cleanupService = cleanupService;
	}

	public void validateNewFolder(String newFolder) {
		if (newFolder == null || newFolder.isBlank() || !Files.isDirectory(Path.of(newFolder))) {
			throw new IllegalArgumentException(message("backend.folder.newInvalid"));
		}
	}

	@Async(AsyncConfig.TASK_EXECUTOR)
	public void switchLibrary(String oldFolder, String newFolder, String username) {
		try {
			inventoryWatchService.pause();

			waitForCancellation();

			int removed = oldFolder == null || oldFolder.isBlank() ? 0 : cleanupService.clear(oldFolder);

			appSettingService.update(SettingsConstants.WATCH_FOLDER, newFolder, username);

			inventoryWatchService.reconfigureAndInventory();

			log.info("Library switched from {} to {}. Catalog entries removed={}", oldFolder, newFolder, removed);
		} catch (InterruptedException exception) {
			// A cancellation or shutdown interrupting the switch is expected, not a
			// failure worth an ERROR with a stack trace.
			log.debug("Library switch from {} to {} was interrupted", oldFolder, newFolder, exception);
			Thread.currentThread().interrupt();

			recoverInventoryMonitoring();
		} catch (Exception exception) {
			log.error("Could not switch monitored library from {} to {}", oldFolder, newFolder, exception);

			recoverInventoryMonitoring();
		}
	}

	private void recoverInventoryMonitoring() {
		try {
			inventoryWatchService.reconfigureAndInventory();
		} catch (Exception recoveryException) {
			log.error("Could not recover inventory monitoring after failed library switch", recoveryException);
		}
	}

	private void waitForCancellation() throws InterruptedException {
		long deadline = System.currentTimeMillis() + CANCELLATION_TIMEOUT_MILLIS;

		while (executionQueryService.active().isPresent()) {
			cancellationService.requestAllCancellations();

			if (System.currentTimeMillis() >= deadline) {
				throw new IllegalStateException(message("backend.execution.cancelTimeout"));
			}

			Thread.sleep(CANCELLATION_POLL_MILLIS);
		}
	}
}