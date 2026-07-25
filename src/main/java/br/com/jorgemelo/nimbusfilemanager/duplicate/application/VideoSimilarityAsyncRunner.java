package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.AsyncConfig;

/**
 * Computes the "Vídeos semelhantes" grouping in the background so the
 * Duplicados screen never blocks on it. Lives in its own bean so the
 * {@code @Async} proxy is honored and its run state stays independent from the
 * photo runner; the claim/track/release logic itself is the shared
 * {@link SimilarityGroupingRunner}.
 */
@Service
public class VideoSimilarityAsyncRunner {

	private final SimilarityGroupingRunner runner;

	public VideoSimilarityAsyncRunner(VideoSimilarityService videoSimilarityService) {
		this.runner = new SimilarityGroupingRunner(videoSimilarityService);
	}

	public synchronized boolean start(int minSimilarityPercent) {
		return runner.start(minSimilarityPercent);
	}

	@Async(AsyncConfig.VISUAL_ANALYSIS_EXECUTOR)
	public void run(int minSimilarityPercent) {
		runner.run(minSimilarityPercent);
	}

	public boolean isRunning() {
		return runner.isRunning();
	}

	public int processed() {
		return runner.processed();
	}

	public int total() {
		return runner.total();
	}

	public int percent() {
		return runner.percent();
	}
}