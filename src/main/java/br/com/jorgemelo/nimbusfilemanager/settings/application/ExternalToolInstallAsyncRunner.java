package br.com.jorgemelo.nimbusfilemanager.settings.application;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.ExternalToolStatus;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.ToolInstallSnapshot;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.AsyncConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the tool installation in the background so the admin request returns
 * immediately - the archive is around 70 MB. Lives in its own bean so the
 * {@code @Async} proxy is honored.
 */
@Slf4j
@Service
public class ExternalToolInstallAsyncRunner {

	private final ExternalToolInstaller installer;
	private final ExternalToolInstallProgress progress;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicReference<String> lastError = new AtomicReference<>();
	private final AtomicReference<ExternalToolStatus> lastResult = new AtomicReference<>();

	public ExternalToolInstallAsyncRunner(ExternalToolInstaller installer, ExternalToolInstallProgress progress) {
		this.installer = installer;
		this.progress = progress;
	}

	/** @return false when an installation is already in progress. */
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
	public void install() {
		try {
			lastResult.set(installer.install());
		} catch (Exception e) {
			log.error("External tool installation failed", e);

			lastError.set(e.getMessage());
		} finally {
			running.set(false);
		}
	}

	public boolean isRunning() {
		return running.get();
	}

	public ToolInstallSnapshot progress() {
		return progress.snapshot();
	}

	public String lastError() {
		return lastError.get();
	}

	public ExternalToolStatus lastResult() {
		return lastResult.get();
	}
}