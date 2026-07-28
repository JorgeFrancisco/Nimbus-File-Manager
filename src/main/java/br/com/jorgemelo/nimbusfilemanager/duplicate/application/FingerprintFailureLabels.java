package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.List;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintFailureResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.FingerprintFailureDetail;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * Turns a fingerprint failure into what the screen shows. The reason is decided
 * and worded here so the browser never maps a code to a sentence, and so the
 * one case that means lost data is flagged by the server instead of by a
 * comparison in JavaScript.
 */
@Component
public class FingerprintFailureLabels extends LocalizedComponent {

	public List<FingerprintFailureResponse> describe(List<FingerprintFailureDetail> failures) {
		return failures.stream().map(this::describe).toList();
	}

	private FingerprintFailureResponse describe(FingerprintFailureDetail failure) {
		FingerprintFailureReason reason = failure.reason() == null ? FingerprintFailureReason.UNKNOWN
				: failure.reason();

		return new FingerprintFailureResponse(failure.path(), reason.name(), label(reason),
				reason == FingerprintFailureReason.CORRUPTED_FILE, failure.error());
	}

	private String label(FingerprintFailureReason reason) {
		return switch (reason) {
		case CORRUPTED_FILE -> message("backend.fingerprint.reason.corruptedFile");
		case NOT_AN_IMAGE -> message("backend.fingerprint.reason.notAnImage");
		case UNSUPPORTED_FORMAT -> message("backend.fingerprint.reason.unsupportedFormat");
		case DECODER_REFUSED -> message("backend.fingerprint.reason.decoderRefused");
		case UNKNOWN -> message("backend.fingerprint.reason.unknown");
		};
	}
}