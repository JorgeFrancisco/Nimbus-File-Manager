package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationPathKey;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
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
 * {@code OrganizationReconcileApply} defers (no-op) when
 * the tree is locked, rather than corrupting it.
 */
@Slf4j
@Service
@Profile(NimbusProfiles.APP)
public class ReconcileScheduler {

	/**
	 * Wait a bit after startup so booting (and any startup inventory) settles
	 * before the first reconcile.
	 */
	private static final long INITIAL_DELAY_MILLIS = 60_000;

	private final AppSettingService appSettingService;
	private final ExecutionEnqueueService executionEnqueueService;
	private final long reconciliationIntervalMillis;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "nimbus-file-manager-reconcile");

		thread.setDaemon(true);

		return thread;
	});
	private volatile boolean shuttingDown;

	public ReconcileScheduler(AppSettingService appSettingService,
			ExecutionEnqueueService executionEnqueueService, NimbusFileManagerProperties properties) {
		this.appSettingService = appSettingService;
		this.executionEnqueueService = executionEnqueueService;
		this.reconciliationIntervalMillis = properties.inventory().reconciliationIntervalMillis();

		executor.scheduleWithFixedDelay(this::runOnce, INITIAL_DELAY_MILLIS, reconciliationIntervalMillis,
				TimeUnit.MILLISECONDS);
	}

	/**
	 * One reconcile pass. Package-private so it can be exercised directly in tests
	 * without the scheduler.
	 */
	final void runOnce() {
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

			// Queued, never run here. An empty pass is not cheap - it walks the whole
			// tree and reads every catalogued location under it, measured at roughly
			// 40 microseconds per file, which is about six seconds over a 145k-file
			// library, every five minutes. That belongs in the worker.
			//
			// Admitted only while nothing equivalent is active, which is stricter than
			// the queue's ordinary 1 + 1 and has to be. A pass over 146k files was
			// measured at five to six minutes against this five minute tick, so "one
			// running and one waiting" was permanently full: every pass was followed
			// by the successor the previous tick left, and the tick after that left
			// another - five reconciles in fifteen minutes over one library. A timer
			// loses nothing by being refused; it asks again by definition.
			executionEnqueueService.enqueueUnlessAlreadyActive(Execution.builder()
					.executionType(ExecutionType.RECONCILE)
					.triggerEvent(ExecutionTrigger.TIMER).sourcePath(folder.toString())
					.recursive(appSettingService.booleanValue(SettingsConstants.WATCH_RECURSIVE, true))
					.executeFlag(true).dedupKey(OperationPathKey.canonical(folder))
					.statusMessage(StatusMessage.code(ExecutionMessages.RECONCILE_REPAIRED)).build());
		} catch (Exception e) {
			// A deferred/lock-contention response is normal (the apply returns it,
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