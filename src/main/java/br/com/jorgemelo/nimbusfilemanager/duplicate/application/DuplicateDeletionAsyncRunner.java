package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeletionResult;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.AsyncConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * Moves the selected duplicates to quarantine in the background so the
 * Duplicados screen never blocks on the sequential secure moves (SHA-256 +
 * verify per file). Lives in its own bean so the {@code @Async} proxy is
 * honored; callers do {@code if (runner.start(n)) runner.run(ids)} - the guard
 * is evaluated synchronously and the heavy work runs off-thread. Only one
 * deletion runs at a time; the screen polls
 * {@link #processed()}/{@link #total()} to show a "Movendo X de N" bar and
 * reads {@link #lastResult()} once {@link #isRunning()} turns false.
 */
@Slf4j
@Service
public class DuplicateDeletionAsyncRunner extends LocalizedComponent {

	private final DuplicateDeletionService duplicateDeletionService;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicInteger processed = new AtomicInteger();
	private final AtomicInteger total = new AtomicInteger();
	private final AtomicReference<DuplicateDeletionResult> lastResult = new AtomicReference<>();
	private volatile Locale requestedLocale = Locale.forLanguageTag("pt-BR");

	public DuplicateDeletionAsyncRunner(DuplicateDeletionService duplicateDeletionService) {
		this.duplicateDeletionService = duplicateDeletionService;
	}

	/**
	 * Claims a background deletion, unless one is already in progress. Returns true
	 * only when a run was claimed; the caller then invokes {@link #run(Collection)}
	 * (the off-thread method).
	 */
	public synchronized boolean start(int totalHint) {
		if (!running.compareAndSet(false, true)) {
			return false;
		}

		processed.set(0);
		total.set(Math.max(0, totalHint));
		lastResult.set(null);
		requestedLocale = LocaleContextHolder.getLocale();

		return true;
	}

	@Async(AsyncConfig.TASK_EXECUTOR)
	public void run(Collection<UUID> publicIds) {
		Locale previousLocale = LocaleContextHolder.getLocale();

		LocaleContextHolder.setLocale(requestedLocale);

		try {
			lastResult.set(duplicateDeletionService.delete(publicIds, (done, count) -> {
				processed.set(done);

				total.set(count);
			}));
		} catch (RuntimeException e) {
			log.error("Duplicate deletion failed for {} file(s)", total.get(), e);

			lastResult.set(new DuplicateDeletionResult(true, total.get(), 0, 0, total.get(), null,
					message("backend.duplicates.deletionFailed")));
		} finally {
			LocaleContextHolder.setLocale(previousLocale);

			running.set(false);
		}
	}

	/**
	 * Releases the claim taken by {@link #start(int)} when the async submission of
	 * {@link #run(Collection)} is rejected (shared executor saturated or shutting
	 * down). Without it the {@code running} flag would stay {@code true} forever
	 * and every future deletion would be refused until the app restarts, because
	 * the {@code finally} in {@link #run(Collection)} never runs when the task is
	 * never submitted.
	 */
	public void releaseRejectedSubmission() {
		lastResult.set(new DuplicateDeletionResult(true, total.get(), 0, 0, total.get(), null,
				message("backend.duplicates.deletionFailed")));

		running.set(false);
	}

	public boolean isRunning() {
		return running.get();
	}

	public int processed() {
		return processed.get();
	}

	public int total() {
		return total.get();
	}

	/**
	 * Result of the last completed deletion, or {@code null} while one is running
	 * or none has run.
	 */
	public DuplicateDeletionResult lastResult() {
		return lastResult.get();
	}

	/**
	 * Percent complete (0-100) of the current deletion, or 0 while the total is
	 * unknown.
	 */
	public double percent() {
		int count = total.get();

		return count <= 0 ? 0 : Math.min(100, (int) Math.round(processed.get() * 100.0 / count));
	}
}