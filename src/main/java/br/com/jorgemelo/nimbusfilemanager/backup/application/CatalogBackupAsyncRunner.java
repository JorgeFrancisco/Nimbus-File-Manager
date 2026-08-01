package br.com.jorgemelo.nimbusfilemanager.backup.application;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.BackupSnapshot;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.AsyncConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the backup and the restore in the background, so the request returns
 * before the work does.
 *
 * <p>
 * Both were synchronous, and a catalog of a few hundred MB took about a minute
 * with nothing on screen - which reads as a hang, and on a restore invites
 * closing the browser at the exact moment the tables have been emptied and not
 * yet loaded. Lives in its own bean so the {@code @Async} proxy is honored.
 *
 * <p>
 * One operation at a time, whichever it is: a backup taken while a restore is
 * replacing the same tables would capture neither the old catalog nor the new.
 */
@Slf4j
@Service
public class CatalogBackupAsyncRunner {

	private final CatalogBackupService backupService;
	private final BackupProgress progress;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicReference<String> lastError = new AtomicReference<>();
	private final AtomicReference<String> lastResult = new AtomicReference<>();

	public CatalogBackupAsyncRunner(CatalogBackupService backupService, BackupProgress progress) {
		this.backupService = backupService;
		this.progress = progress;
	}

	/** @return false when an operation is already in progress. */
	public boolean start() {
		if (!running.compareAndSet(false, true)) {
			return false;
		}

		lastError.set(null);
		lastResult.set(null);
		progress.reset();

		return true;
	}

	@Async(AsyncConfig.TASK_EXECUTOR)
	public void create() {
		try {
			lastResult.set(backupService.create().name());
		} catch (Exception exception) {
			log.error("Catalog backup failed", exception);

			lastError.set(exception.getMessage());
		} finally {
			progress.reset();

			running.set(false);
		}
	}

	@Async(AsyncConfig.TASK_EXECUTOR)
	public void restore(String name) {
		try {
			backupService.restore(name);

			lastResult.set(name);
		} catch (Exception exception) {
			log.error("Catalog restore failed", exception);

			lastError.set(exception.getMessage());
		} finally {
			progress.reset();

			running.set(false);
		}
	}

	public boolean isRunning() {
		return running.get();
	}

	public BackupSnapshot progress() {
		return progress.snapshot();
	}

	public String lastError() {
		return lastError.get();
	}

	public String lastResult() {
		return lastResult.get();
	}
}