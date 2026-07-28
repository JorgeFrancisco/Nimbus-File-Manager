package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LocationRebuildResult;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationRebuildScope;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.AsyncConfig;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ProgressMath;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs a location rebuild in the background and exposes its progress
 * (processed/total) so the admin screen can display it. Lives in its own bean
 * so the {@code @Async} proxy is honored.
 */
@Slf4j
@Service
public class LocationRebuildAsyncRunner {

	private final LocationRebuildService locationRebuildService;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicLong processed = new AtomicLong();
	private final AtomicLong total = new AtomicLong();
	private volatile long startedAtMillis;
	private final AtomicReference<LocationRebuildResult> lastResult = new AtomicReference<>();
	private final AtomicReference<String> lastError = new AtomicReference<>();

	public LocationRebuildAsyncRunner(LocationRebuildService locationRebuildService) {
		this.locationRebuildService = locationRebuildService;
	}

	/** @return false when a rebuild is already in progress. */
	public boolean start(LocationRebuildScope scope) {
		if (!running.compareAndSet(false, true)) {
			return false;
		}

		processed.set(0);
		lastError.set(null);
		lastResult.set(null);
		startedAtMillis = System.currentTimeMillis();

		try {
			total.set(locationRebuildService.countCandidates(scope));
		} catch (Exception _) {
			total.set(0);
		}

		return true;
	}

	@Async(AsyncConfig.GEOLOCATION_EXECUTOR)
	public void rebuild(LocationRebuildScope scope) {
		try {
			lastResult.set(locationRebuildService.rebuild(scope, processed::set));
		} catch (Exception e) {
			log.error("Location rebuild failed. scope={}", scope, e);

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

	public LocationRebuildResult lastResult() {
		return lastResult.get();
	}

	public String lastError() {
		return lastError.get();
	}
}