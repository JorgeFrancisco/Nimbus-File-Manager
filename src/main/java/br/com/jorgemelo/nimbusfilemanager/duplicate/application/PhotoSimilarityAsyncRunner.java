package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.AsyncConfig;

/**
 * Computes the "Fotos semelhantes" grouping in the background so the Duplicados
 * screen never blocks on it. Lives in its own bean so the {@code @Async} proxy
 * is honored and its run state stays independent from the video runner; the
 * claim/track/release logic itself is the shared
 * {@link SimilarityGroupingRunner}.
 */
@Service
public class PhotoSimilarityAsyncRunner {

	private final SimilarityGroupingRunner runner;

	public PhotoSimilarityAsyncRunner(PhotoSimilarityService photoSimilarityService) {
		this.runner = new SimilarityGroupingRunner(photoSimilarityService);
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

	public double percent() {
		return runner.percent();
	}
}