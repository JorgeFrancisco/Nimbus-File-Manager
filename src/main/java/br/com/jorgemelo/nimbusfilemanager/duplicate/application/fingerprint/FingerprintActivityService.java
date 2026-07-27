package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.util.Optional;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.BackgroundJobActivity;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * Reports a running fingerprint backlog as a background job the page banner can
 * show, so hashing thousands of files stops being invisible work that only
 * announces itself as a slow machine.
 *
 * <p>
 * Video comes first when both are running: it is the one that costs ffmpeg
 * processes and competes with a conversion, so it is the one worth naming.
 */
@Service
public class FingerprintActivityService extends LocalizedComponent {

	private static final String DUPLICATES_LINK = "/app/duplicates";

	private final PhashBacklogAsyncRunner photoBacklogRunner;
	private final VideoFingerprintBacklogAsyncRunner videoBacklogRunner;

	public FingerprintActivityService(PhashBacklogAsyncRunner photoBacklogRunner,
			VideoFingerprintBacklogAsyncRunner videoBacklogRunner) {
		this.photoBacklogRunner = photoBacklogRunner;
		this.videoBacklogRunner = videoBacklogRunner;
	}

	/** The running backlog, or empty when neither is working. */
	public Optional<BackgroundJobActivity> current() {
		if (videoBacklogRunner.isRunning()) {
			return Optional.of(activity("backend.fingerprint.videoRunning", videoBacklogRunner.status(),
					videoBacklogRunner.etaSeconds()));
		}

		if (photoBacklogRunner.isRunning()) {
			return Optional.of(activity("backend.fingerprint.photoRunning", photoBacklogRunner.status(),
					photoBacklogRunner.etaSeconds()));
		}

		return Optional.empty();
	}

	/**
	 * Counts come from the backlog status, never from the runner's own counter: the
	 * runner counts what the current run has done, so a run that just started
	 * reported "0 of 6342" next to a 96% bar - the same numbers the Duplicados
	 * screen shows, disagreeing with each other on the same page.
	 */
	private BackgroundJobActivity activity(String labelKey, FingerprintBacklogStatus status, long etaSeconds) {
		return new BackgroundJobActivity(message(labelKey), DUPLICATES_LINK, status.done() + status.failed(),
				status.total(), status.percent(), etaSeconds);
	}
}