package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogProgress;
import br.com.jorgemelo.nimbusfilemanager.execution.application.EtaLabels;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.EtaEstimate;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.OtherFingerprintProgress;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * Everything the fingerprint panel of a similarity tab shows, assembled in one
 * place: the tab's own backlog, its estimate, and - only when it explains
 * something - the other medium's fingerprint.
 *
 * <p>
 * The counts, the percentage and the estimate are unchanged; what is new is the
 * last part. A backlog can sit at the same number for hours while the machine is
 * genuinely busy, because the work in flight is the other medium's. The panel
 * now says which one that is and how far it has got, so a bar that is not moving
 * stops looking like a bar that is stuck.
 *
 * <p>
 * <b>It reports a fact, not a policy.</b> The sentence names whichever
 * fingerprint is running - never "videos are waiting for photos". Which of the
 * two goes first is the queue's rule, it can change, and a screen that spelled
 * it out would go on asserting it after it had. The condition is symmetrical for
 * the same reason.
 *
 * <p>
 * <b>And only when it explains something.</b> With this tab's own fingerprint
 * running, its own panel already accounts for the wait; with the other one idle
 * there is nothing to account for. Both answer with no context at all - which is
 * also what keeps the four counting queries behind that percentage from being
 * issued on the polls where nobody would see them.
 */
@Component
public class FingerprintBacklogProgressReader extends LocalizedComponent {

	private final PhashBacklogService phashBacklogService;
	private final VideoFingerprintBacklogService videoFingerprintBacklogService;
	private final FingerprintRunReader fingerprintRunReader;
	private final EtaLabels etaLabels;

	public FingerprintBacklogProgressReader(EtaLabels etaLabels, PhashBacklogService phashBacklogService,
			VideoFingerprintBacklogService videoFingerprintBacklogService, FingerprintRunReader fingerprintRunReader) {
		this.phashBacklogService = phashBacklogService;
		this.videoFingerprintBacklogService = videoFingerprintBacklogService;
		this.fingerprintRunReader = fingerprintRunReader;
		this.etaLabels = etaLabels;
	}

	/** @param tab which fingerprint the tab being rendered is about */
	public FingerprintBacklogProgress forTab(ExecutionType tab) {
		boolean running = fingerprintRunReader.isRunning(tab);

		EtaEstimate eta = fingerprintRunReader.eta(tab);

		return FingerprintBacklogProgress.of(statusOf(tab), eta, etaLabels.label(eta), running, other(tab, running));
	}

	/**
	 * @param running whether this tab's own fingerprint is running, passed in
	 * rather than asked again - the answer is already known and it costs a query
	 * @return the other fingerprint when it is the one running and this one is not,
	 * {@code null} otherwise - the ordinary answer, and the cheap one
	 */
	private OtherFingerprintProgress other(ExecutionType tab, boolean running) {
		if (running) {
			return null;
		}

		ExecutionType other = tab == ExecutionType.FINGERPRINT_PHOTO ? ExecutionType.FINGERPRINT_VIDEO
				: ExecutionType.FINGERPRINT_PHOTO;

		// Asked before anything is counted, so the answer "nothing to show" never pays
		// for a status() it would only throw away.
		if (!fingerprintRunReader.isRunning(other)) {
			return null;
		}

		String label = other == ExecutionType.FINGERPRINT_PHOTO ? "backend.duplicates.otherFingerprint.photos"
				: "backend.duplicates.otherFingerprint.videos";

		return new OtherFingerprintProgress(message(label), statusOf(other).percent());
	}

	private FingerprintBacklogStatus statusOf(ExecutionType type) {
		return type == ExecutionType.FINGERPRINT_PHOTO ? phashBacklogService.status()
				: videoFingerprintBacklogService.status();
	}
}