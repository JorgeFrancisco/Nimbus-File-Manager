package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.AsyncConfig;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ProgressMath;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs a metadata rebuild in the background and exposes its progress
 * (processed/total, percentage and estimate) so the settings screen can display
 * it instead of holding a request open for the whole run. Lives in its own bean
 * so the {@code @Async} proxy is honored, mirroring the location rebuild.
 */
@Slf4j
@Service
public class MetadataRebuildAsyncRunner {

	private final MetadataRebuildService metadataRebuildService;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicLong processed = new AtomicLong();
	private final AtomicLong total = new AtomicLong();
	private volatile long startedAtMillis;
	private final AtomicReference<MetadataRebuildResponse> lastResult = new AtomicReference<>();
	private final AtomicReference<String> lastError = new AtomicReference<>();

	public MetadataRebuildAsyncRunner(MetadataRebuildService metadataRebuildService) {
		this.metadataRebuildService = metadataRebuildService;
	}

	/** @return false when a rebuild is already in progress. */
	public boolean start(MetadataRebuildRequest request) {
		if (!running.compareAndSet(false, true)) {
			return false;
		}

		processed.set(0);
		lastError.set(null);
		lastResult.set(null);
		startedAtMillis = System.currentTimeMillis();

		try {
			total.set(metadataRebuildService.countCandidates(request));
		} catch (Exception _) {
			// An unreadable total only costs the percentage and the estimate, so the
			// rebuild still starts and reports how many files it has processed.
			total.set(0);
		}

		return true;
	}

	@Async(AsyncConfig.TASK_EXECUTOR)
	public void rebuild(MetadataRebuildRequest request) {
		try {
			lastResult.set(metadataRebuildService.rebuild(request, processed::set));
		} catch (Exception e) {
			log.error("Metadata rebuild failed. sourcePath={}", request.sourcePath(), e);

			lastError.set(e.getMessage());
		} finally {
			running.set(false);
		}
	}

	public boolean isRunning() {
		return running.get();
	}

	public long processed() {
		return processed.get();
	}

	public long total() {
		return total.get();
	}

	/** 0-100, or -1 when the total is unknown. */
	public double percent() {
		return ProgressMath.percent(processed.get(), total.get());
	}

	/** Estimated seconds remaining by average rate, or -1 when unknown. */
	public long etaSeconds() {
		return ProgressMath.etaSeconds(System.currentTimeMillis() - startedAtMillis, processed.get(), total.get());
	}

	public MetadataRebuildResponse lastResult() {
		return lastResult.get();
	}

	public String lastError() {
		return lastError.get();
	}
}