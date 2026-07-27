package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintBacklogResumer;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.AsyncConfig;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ProgressMath;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs a conversion batch off the request thread. Unlike every other background
 * job in the application this one takes <em>minutes per file</em>, so blocking
 * the screen on it was never an option: the controller claims a run, returns
 * immediately, and the screen polls the counters below for a two-level progress
 * bar (files done, and how far into the current encode ffmpeg is).
 *
 * <p>
 * Only one batch runs at a time - a second concurrent H.265 encode would make
 * both slower, not faster - and the claim is taken synchronously in
 * {@link #start(int)} so two requests can never both start one.
 */
@Slf4j
@Service
public class VideoConversionAsyncRunner extends LocalizedComponent {

	private final VideoConversionService videoConversionService;
	private final FingerprintBacklogResumer fingerprintBacklogResumer;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicBoolean cancelled = new AtomicBoolean(false);
	private final AtomicInteger processed = new AtomicInteger();
	private final AtomicInteger total = new AtomicInteger();
	private final AtomicInteger filePercent = new AtomicInteger();
	private final AtomicLong startedAtMillis = new AtomicLong();
	private final AtomicReference<String> currentFile = new AtomicReference<>();
	private final AtomicReference<ConversionResult> lastResult = new AtomicReference<>();
	private volatile Locale requestedLocale = Locale.forLanguageTag("pt-BR");

	public VideoConversionAsyncRunner(VideoConversionService videoConversionService,
			FingerprintBacklogResumer fingerprintBacklogResumer) {
		this.videoConversionService = videoConversionService;
		this.fingerprintBacklogResumer = fingerprintBacklogResumer;
	}

	/**
	 * Claims a background conversion unless one is already in progress. Returns
	 * true only when a run was claimed; the caller then invokes
	 * {@link #run(Collection, ConversionOptions)}.
	 */
	public synchronized boolean start(int totalHint) {
		if (!running.compareAndSet(false, true)) {
			return false;
		}

		cancelled.set(false);
		processed.set(0);
		total.set(Math.max(0, totalHint));
		filePercent.set(0);
		startedAtMillis.set(System.currentTimeMillis());
		currentFile.set(null);
		lastResult.set(null);
		requestedLocale = LocaleContextHolder.getLocale();

		return true;
	}

	/**
	 * Asks the running batch to stop. It takes effect on the file being encoded -
	 * ffmpeg is killed and its half-written output deleted - and no further file is
	 * started. Returns false when there was nothing to stop.
	 */
	public boolean cancel() {
		if (!running.get()) {
			return false;
		}

		cancelled.set(true);

		return true;
	}

	public boolean isCancelled() {
		return cancelled.get();
	}

	@Async(AsyncConfig.TASK_EXECUTOR)
	public void run(Collection<UUID> publicIds, ConversionOptions options) {
		Locale previousLocale = LocaleContextHolder.getLocale();

		// The batch outlives the request that started it, so the locale it renders its
		// messages in has to be carried over explicitly.
		LocaleContextHolder.setLocale(requestedLocale);

		try {
			lastResult.set(videoConversionService.convert(publicIds, options, this::report, cancelled::get));
		} catch (RuntimeException e) {
			log.error("Video conversion failed for {} file(s)", total.get(), e);

			lastResult.set(ConversionResult.empty(message("backend.conversion.failed")));
		} finally {
			LocaleContextHolder.setLocale(previousLocale);

			running.set(false);

			// The backlogs stepped aside for this batch and have nobody to restart them:
			// the conversion is the only one that knows it is over. Resuming here is what
			// keeps "paused during the conversion" from meaning "paused until the next
			// restart".
			fingerprintBacklogResumer.resume();
		}
	}

	/**
	 * Releases the claim taken by {@link #start(int)} when the async submission is
	 * rejected (shared executor saturated or shutting down). Without it the running
	 * flag would stay true forever and every later conversion would be refused,
	 * because the {@code finally} above never runs for a task that was never
	 * submitted.
	 */
	public void releaseRejectedSubmission() {
		lastResult.set(ConversionResult.empty(message("backend.conversion.failed")));

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

	public int filePercent() {
		return filePercent.get();
	}

	public String currentFile() {
		return currentFile.get();
	}

	/** Result of the last finished batch, or null while one runs. */
	public ConversionResult lastResult() {
		return lastResult.get();
	}

	/**
	 * Seconds remaining by the same arithmetic every other long job in the app uses
	 * ({@link ProgressMath}), or -1 while it cannot be estimated. Progress is
	 * measured in hundredths of a file instead of whole files, so a batch of one
	 * long video still produces an estimate.
	 */
	public long etaSeconds() {
		if (!running.get()) {
			return -1;
		}

		long done = processed.get() * 100L + Math.clamp(filePercent.get(), 0, 100);

		return ProgressMath.etaSeconds(System.currentTimeMillis() - startedAtMillis.get(), done, total.get() * 100L);
	}

	/**
	 * Overall progress, counting how far into the current file the encoder is so a
	 * batch of one long video still advances visibly.
	 */
	public int percent() {
		int count = total.get();

		if (count <= 0) {
			return 0;
		}

		if (processed.get() >= count) {
			return 100;
		}

		double done = processed.get() + Math.min(100, filePercent.get()) / 100.0;

		// Floored, and held below 100 while any file is still being written: rounding
		// showed a finished bar with the last video at 88% in a batch of 25, and at
		// barely half of it in a batch of 100 - the fuller the batch, the earlier the
		// lie.
		return Math.clamp((int) Math.floor(done * 100 / count), 0, 99);
	}

	private void report(int done, int count, int percentOfFile, String file) {
		processed.set(done);
		total.set(count);
		filePercent.set(percentOfFile);
		currentFile.set(file);
	}
}