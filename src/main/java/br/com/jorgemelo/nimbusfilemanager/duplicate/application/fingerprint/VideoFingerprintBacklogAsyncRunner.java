package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.time.Clock;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintJobRunRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.AsyncConfig;
import jakarta.annotation.PreDestroy;

/**
 * Runs the video fingerprint backlog in the background. Lives in its own bean
 * so the {@code @Async} proxy is honored and so it keeps run state independent
 * from the photo runner; the lifecycle itself (start guard, drain/finalize,
 * progress/ETA, cancellation) is the shared {@link FingerprintJobRunner}.
 */
@Service
public class VideoFingerprintBacklogAsyncRunner {

	private final FingerprintJobRunner runner;

	public VideoFingerprintBacklogAsyncRunner(VideoFingerprintBacklogService backlogService,
			FingerprintJobRunRepository jobRunRepository, Clock clock, BackgroundWorkGate backgroundWorkGate) {
		this.runner = new FingerprintJobRunner(backlogService, jobRunRepository, clock, backgroundWorkGate);
	}

	public synchronized boolean start() {
		return runner.start();
	}

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