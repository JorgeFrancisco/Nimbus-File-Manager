package br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto;

import java.nio.file.Path;

/**
 * What came out of the conversation about restoring one file: either something
 * to tell the person, or the move the restore now is.
 *
 * <p>
 * The two are mutually exclusive on purpose. A name collision and a missing
 * origin folder are questions - they end the request with an answer the screen
 * turns into a dialog, and nothing is queued. Anything else is a decision, and
 * a decision is what the queue can carry: these two paths, and no choices left
 * to make.
 *
 * @param answer the outcome to report without queuing anything, or {@code null}
 * when the restore may proceed
 * @param quarantined the copy to move back
 * @param destination the exact file to create, decided here so the worker never
 * has to choose
 */
public record QuarantineRestorePlan(QuarantineRestoreResult answer, Path quarantined, Path destination) {

	public static QuarantineRestorePlan answered(QuarantineRestoreResult answer) {
		return new QuarantineRestorePlan(answer, null, null);
	}

	public static QuarantineRestorePlan move(Path quarantined, Path destination) {
		return new QuarantineRestorePlan(null, quarantined, destination);
	}

	public boolean decided() {
		return answer == null;
	}
}