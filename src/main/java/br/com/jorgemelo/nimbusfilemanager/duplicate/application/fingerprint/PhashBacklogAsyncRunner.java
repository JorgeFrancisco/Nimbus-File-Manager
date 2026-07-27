package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.time.Clock;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintJobRunRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.AsyncConfig;
import jakarta.annotation.PreDestroy;

/**
 * Runs the photo fingerprint backlog in the background. Lives in its own bean
 * so the {@code @Async} proxy is honored; callers do
 * {@code if (runner.start()) runner.run()} so the guard is evaluated
 * synchronously and the drain runs off-thread.
 *
 * <p>
 * All the lifecycle - the idempotent, inventory-aware {@link #start()} guard,
 * the drain-and-finalize cycle, progress/ETA and cancellation - lives in the
 * shared {@link FingerprintJobRunner}; this bean only adds the {@code @Async}
 * boundary and keeps its own independent run state (it holds its own runner).
 */
@Service
public class PhashBacklogAsyncRunner {

	private final FingerprintJobRunner runner;

	public PhashBacklogAsyncRunner(PhashBacklogService backlogService, FingerprintJobRunRepository jobRunRepository,
			Clock clock) {
		this.runner = new FingerprintJobRunner(backlogService, jobRunRepository, clock);
	}

	/**
	 * @return false (and starts nothing) when an inventory is active, nothing is
	 *         pending, or a run is already in progress. Idempotent.
	 */
	public synchronized boolean start() {
		return runner.start();
	}

	/** Prepares a fingerprint-only rebuild without racing an active worker. */
	public synchronized boolean prepareRebuild() {
		return runner.prepareRebuild();
	}

	@Async(AsyncConfig.VISUAL_ANALYSIS_EXECUTOR)
	public void run() {
		runner.run();
	}

	@PreDestroy
	public void stop() {
		runner.stop();
	}

	public boolean isRunning() {
		return runner.isRunning();
	}

	public long processed() {
		return runner.processed();
	}

	public long failed() {
		return runner.failed();
	}

	/**
	 * Estimated seconds remaining from the rate at which pending photos disappear.
	 */
	public long etaSeconds() {
		return runner.etaSeconds();
	}

	public String lastError() {
		return runner.lastError();
	}

	public FingerprintBacklogStatus status() {
		return runner.status();
	}
}