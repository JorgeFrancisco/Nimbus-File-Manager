package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.util.Optional;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.BackgroundJobActivity;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * Reports a running fingerprint backlog as a background job the page banner can
 * show, so hashing thousands of files stops being invisible work that only
 * announces itself as a slow machine.
 *
 * <p>
 * Whether one is running is a question to the queue now, not to a field: the
 * drain happens in the worker, so the process rendering this page has nothing in
 * memory to ask. That is also what makes the banner survive - it appears for a
 * run this application never started and keeps appearing after it restarts.
 *
 * <p>
 * Video comes first when both are running: it is the one that costs ffmpeg
 * processes and competes with a conversion, so it is the one worth naming.
 */
@Service
public class FingerprintActivityService extends LocalizedComponent {

	private static final String DUPLICATES_LINK = "/app/duplicates";

	private final PhashBacklogService photoBacklog;
	private final VideoFingerprintBacklogService videoBacklog;
	private final FingerprintRunReader fingerprintRunReader;

	public FingerprintActivityService(PhashBacklogService photoBacklog, VideoFingerprintBacklogService videoBacklog,
			FingerprintRunReader fingerprintRunReader) {
		this.photoBacklog = photoBacklog;
		this.videoBacklog = videoBacklog;
		this.fingerprintRunReader = fingerprintRunReader;
	}

	/** The running backlog, or empty when neither is working. */
	public Optional<BackgroundJobActivity> current() {
		if (fingerprintRunReader.isRunning(ExecutionType.FINGERPRINT_VIDEO)) {
			return Optional.of(activity("backend.fingerprint.videoRunning", videoBacklog.status(),
					ExecutionType.FINGERPRINT_VIDEO));
		}

		if (fingerprintRunReader.isRunning(ExecutionType.FINGERPRINT_PHOTO)) {
			return Optional.of(activity("backend.fingerprint.photoRunning", photoBacklog.status(),
					ExecutionType.FINGERPRINT_PHOTO));
		}

		return Optional.empty();
	}

	/**
	 * Counts come from the backlog status, never from the run's own counter: the
	 * run counts what it has done, so one that just started reported "0 of 6342"
	 * next to a 96% bar - the same numbers the Duplicados screen shows, disagreeing
	 * with each other on the same page.
	 */
	private BackgroundJobActivity activity(String labelKey, FingerprintBacklogStatus status, ExecutionType type) {
		return new BackgroundJobActivity(message(labelKey), DUPLICATES_LINK, status.done() + status.failed(),
				status.total(), status.percent(), fingerprintRunReader.etaSeconds(type));
	}
}