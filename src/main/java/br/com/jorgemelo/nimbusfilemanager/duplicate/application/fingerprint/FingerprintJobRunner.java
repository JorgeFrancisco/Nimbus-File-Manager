package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintJobStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintJobRun;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintJobRunRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ProgressMath;

/**
 * Neutral background driver for a {@link FingerprintBacklog}: the idempotent,
 * coordinated {@link #start()} guard (refuses while an inventory is active,
 * when nothing is pending, or when a run is already going), the
 * drain-and-finalize cycle that records a {@link FingerprintJobRun}, live
 * progress/ETA, and cooperative cancellation.
 *
 * <p>
 * The photo and video async runner beans each hold one of these and only add
 * the {@code @Async} proxy boundary, so all of this state and lifecycle lives
 * in one place - no per-media duplication - while each media keeps its own
 * runner bean (and therefore its own independent run state).
 */
class FingerprintJobRunner {

	private static final Logger log = LoggerFactory.getLogger(FingerprintJobRunner.class);

	private final FingerprintBacklog backlog;
	private final FingerprintJobRunRepository jobRunRepository;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicBoolean stopRequested = new AtomicBoolean(false);
	private final AtomicLong processed = new AtomicLong();
	private final AtomicLong failed = new AtomicLong();
	private final AtomicLong startedAtMillis = new AtomicLong();
	private final AtomicLong pendingAtStart = new AtomicLong();
	private final AtomicLong terminalAtStart = new AtomicLong();
	private final AtomicReference<Long> currentRunId = new AtomicReference<>();
	private final AtomicReference<String> lastError = new AtomicReference<>();
	private final Clock clock;

	public FingerprintJobRunner(FingerprintBacklog backlog, FingerprintJobRunRepository jobRunRepository, Clock clock) {
		this.backlog = backlog;
		this.jobRunRepository = jobRunRepository;
		this.clock = clock;
	}

	/**
	 * @return false (and starts nothing) when an inventory is active, nothing is
	 *         pending, or a run is already in progress. Idempotent.
	 */
	public synchronized boolean start() {
		if (backlog.pausedByActiveExecution()) {
			return false;
		}

		FingerprintBacklogStatus status = backlog.status();

		if (status.pending() == 0) {
			return false;
		}

		if (!running.compareAndSet(false, true)) {
			return false;
		}

		stopRequested.set(false);
		processed.set(0);
		failed.set(0);
		startedAtMillis.set(System.currentTimeMillis());
		pendingAtStart.set(status.pending());
		terminalAtStart.set(status.done() + status.failed());
		lastError.set(null);

		FingerprintJobRun run = jobRunRepository.save(FingerprintJobRun.builder().kind(backlog.kind())
				.algorithm(backlog.algorithm()).status(FingerprintJobStatus.RUNNING).startedAt(LocalDateTime.now(clock))
				.totalAtStart(status.total()).build());

		currentRunId.set(run.getId());

		return true;
	}

	/** Prepares a fingerprint-only rebuild without racing an active worker. */
	public synchronized boolean prepareRebuild() {
		if (running.get() || backlog.pausedByActiveExecution()) {
			return false;
		}

		backlog.rebuild();

		return start();
	}

	public void run() {
		FingerprintJobStatus finalStatus = FingerprintJobStatus.FINISHED;

		String message = null;

		try {
			DrainResult result = backlog.drainPending(stopRequested::get, (done, failures) -> {
				processed.set(done);
				failed.set(failures);
			});

			if (stopRequested.get() || backlog.pausedByActiveExecution()) {
				finalStatus = FingerprintJobStatus.CANCELLED;
			}

			message = "processed=" + result.processed() + ", failed=" + result.failed();
		} catch (RuntimeException e) {
			finalStatus = FingerprintJobStatus.FAILED;

			lastError.set(e.getMessage());

			message = e.getMessage();

			log.error("Fingerprint backlog failed for {}/{}", backlog.kind(), backlog.algorithm(), e);
		} finally {
			finalizeRun(finalStatus, message);

			running.set(false);
		}
	}

	private void finalizeRun(FingerprintJobStatus status, String message) {
		Long runId = currentRunId.getAndSet(null);

		if (runId == null) {
			return;
		}

		try {
			jobRunRepository.findById(runId).ifPresent(run -> {
				run.setStatus(status);
				run.setFinishedAt(LocalDateTime.now(clock));
				run.setProcessed(processed.get());
				run.setFailed(failed.get());
				run.setMessage(message);

				jobRunRepository.save(run);
			});
		} catch (RuntimeException e) {
			log.warn("Could not finalize fingerprint job run {}", runId, e);
		}
	}

	public void stop() {
		stopRequested.set(true);
	}

	public boolean isRunning() {
		return running.get();
	}

	public long processed() {
		return processed.get();
	}

	public long failed() {
		return failed.get();
	}

	/**
	 * Estimated seconds remaining from the rate at which pending items disappear.
	 */
	public long etaSeconds() {
		if (!running.get()) {
			return -1;
		}

		long initialPending = pendingAtStart.get();
		long currentPending = backlog.status().pending();
		long completed = Math.max(0, initialPending - currentPending);

		return ProgressMath.etaSeconds(System.currentTimeMillis() - startedAtMillis.get(), completed, initialPending);
	}

	public String lastError() {
		return lastError.get();
	}

	public FingerprintBacklogStatus status() {
		return backlog.status();
	}

	/**
	 * Progress as it actually stands, which neither of the two obvious sources
	 * tells on its own. The stored counts only move when a batch is written - two
	 * hundred items at a time - so a screen reading them sits frozen for minutes;
	 * the run counter starts at zero every run, so a resumed backlog reports "25 of
	 * 6342" beside its own 96% bar. What is true is what was already stored when
	 * this run began plus what the run has finished since, whether or not it has
	 * been written yet.
	 */
	public FingerprintBacklogStatus liveStatus() {
		FingerprintBacklogStatus stored = backlog.status();

		if (!running.get()) {
			return stored;
		}

		long unwritten = Math.max(0, terminalAtStart.get() + processed.get() - stored.done() - stored.failed());

		return new FingerprintBacklogStatus(Math.max(0, stored.pending() - unwritten), stored.done() + unwritten,
				stored.failed());
	}
}