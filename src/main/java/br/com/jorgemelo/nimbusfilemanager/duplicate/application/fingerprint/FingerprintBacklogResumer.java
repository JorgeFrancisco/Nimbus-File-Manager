package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;

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
	private final BackgroundWorkGate backgroundWorkGate;

	public FingerprintBacklogResumer(PhashBacklogAsyncRunner photoBacklogRunner,
			VideoFingerprintBacklogAsyncRunner videoBacklogRunner, BackgroundWorkGate backgroundWorkGate) {
		this.photoBacklogRunner = photoBacklogRunner;
		this.videoBacklogRunner = videoBacklogRunner;
		this.backgroundWorkGate = backgroundWorkGate;
	}

	public void resume() {
		// Starting a backlog while the application is closing only queues work that
		// will reach a connection pool already shut - which is how a finished
		// inventory came to end an otherwise clean shutdown with an I/O error against
		// the backend. Nothing is lost: the backlog is what is still pending in the
		// database, and the next start picks it up.
		if (backgroundWorkGate.standDown()) {
			return;
		}

		if (photoBacklogRunner.start()) {
			photoBacklogRunner.run();
		}

		if (videoBacklogRunner.start()) {
			videoBacklogRunner.run();
		}
	}
}