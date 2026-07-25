package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Resumes the video fingerprint backlog after a restart if work remains (and no
 * inventory is active - the runner's {@code start()} enforces that). Recovery
 * of stale {@code RUNNING} job runs is done once, globally, by the photo
 * startup; this only re-kicks the video runner. Lives in its own bean so the
 * calls to the {@code @Async} runner cross a proxy boundary and actually run
 * asynchronously.
 */
@Slf4j
@Component
public class VideoFingerprintBacklogStartup {

	private final VideoFingerprintBacklogAsyncRunner backlogRunner;

	public VideoFingerprintBacklogStartup(VideoFingerprintBacklogAsyncRunner backlogRunner) {
		this.backlogRunner = backlogRunner;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void resumeOnStartup() {
		if (backlogRunner.start()) {
			backlogRunner.run();
		}
	}
}