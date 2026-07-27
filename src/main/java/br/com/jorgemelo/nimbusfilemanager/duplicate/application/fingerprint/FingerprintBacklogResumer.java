package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import org.springframework.stereotype.Component;

/**
 * Restarts the fingerprint backlogs once whatever they stepped aside for is
 * over. Both are resumed: a conversion competes for ffmpeg with photos and
 * videos alike.
 *
 * <p>
 * There is nothing to hand over - {@code start()} is idempotent, refuses while
 * something is still running, and each run reads what is pending from the
 * database - so "resume" is simply starting a fresh run. Lives in its own bean
 * so a caller in another domain depends on one narrow thing instead of on both
 * async runners.
 */
@Component
public class FingerprintBacklogResumer {

	private final PhashBacklogAsyncRunner photoBacklogRunner;
	private final VideoFingerprintBacklogAsyncRunner videoBacklogRunner;

	public FingerprintBacklogResumer(PhashBacklogAsyncRunner photoBacklogRunner,
			VideoFingerprintBacklogAsyncRunner videoBacklogRunner) {
		this.photoBacklogRunner = photoBacklogRunner;
		this.videoBacklogRunner = videoBacklogRunner;
	}

	public void resume() {
		if (photoBacklogRunner.start()) {
			photoBacklogRunner.run();
		}

		if (videoBacklogRunner.start()) {
			videoBacklogRunner.run();
		}
	}
}