package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ReconcileExecutionRecorder;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationReconcileService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs periodic reconciliation of the configured watch folder on its own daemon
 * thread, mirroring how {@code QuarantinePurgeScheduler} schedules its work
 * (the app has no Spring {@code @EnableScheduling}). Unlike the reactive
 * reconcile in {@link InventoryWatchService}, this one is independent of the
 * watcher's running state, so drift heals even while monitoring is stopped. It
 * is concurrency-safe against an active operation because
 * {@link OrganizationReconcileService#reconcileAndApply} defers (no-op) when
 * the tree is locked, rather than corrupting it.
 */
@Slf4j
@Service
public class ReconcileScheduler {

	/**
	 * Wait a bit after startup so booting (and any startup inventory) settles
	 * before the first reconcile.
	 */
	private static final long INITIAL_DELAY_MILLIS = 60_000;

	private final AppSettingService appSettingService;
	private final OrganizationReconcileService organizationReconcileService;
	private final ReconcileExecutionRecorder reconcileExecutionRecorder;
	private final long reconciliationIntervalMillis;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "nimbus-file-manager-reconcile");

		thread.setDaemon(true);

		return thread;
	});
	private volatile boolean shuttingDown;

	public ReconcileScheduler(AppSettingService appSettingService,
			OrganizationReconcileService organizationReconcileService,
			ReconcileExecutionRecorder reconcileExecutionRecorder, NimbusFileManagerProperties properties) {
		this.appSettingService = appSettingService;
		this.organizationReconcileService = organizationReconcileService;
		this.reconcileExecutionRecorder = reconcileExecutionRecorder;
		this.reconciliationIntervalMillis = properties.inventory().reconciliationIntervalMillis();

		executor.scheduleWithFixedDelay(this::runOnce, INITIAL_DELAY_MILLIS, reconciliationIntervalMillis,
				TimeUnit.MILLISECONDS);
	}

	/**
	 * One reconcile pass. Package-private so it can be exercised directly in tests
	 * without the scheduler.
	 */
	void runOnce() {
		try {
			String configuredFolder = appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "");

			if (configuredFolder.isBlank()) {
				return;
			}

			Path folder = Path.of(configuredFolder).toAbsolutePath().normalize();

			if (!Files.isDirectory(folder)) {
				log.debug("Scheduled reconcile skipped: configured watch folder {} does not exist", folder);

				return;
			}

			OrganizationReconcileRequest request = new OrganizationReconcileRequest(configuredFolder,
					appSettingService.booleanValue(SettingsConstants.WATCH_RECURSIVE, true),
					appSettingService.booleanValue(SettingsConstants.WATCH_INCLUDE_HIDDEN, false), Integer.MAX_VALUE);

			OrganizationReconcileResponse response = organizationReconcileService.reconcileAndApply(request);

			reconcileExecutionRecorder.recordIfRepaired(ExecutionTrigger.TIMER, folder, response);
		} catch (Exception e) {
			// A deferred/lock-contention response is normal (reconcileAndApply returns it,
			// never throws), so only genuinely unexpected failures reach here.
			if (shuttingDown || Thread.currentThread().isInterrupted()) {
				log.debug("Scheduled reconcile interrupted during shutdown", e);
			} else {
				log.error("Scheduled reconcile failed", e);
			}
		}
	}

	@PreDestroy
	void shutdown() {
		shuttingDown = true;

		executor.shutdownNow();
	}
}