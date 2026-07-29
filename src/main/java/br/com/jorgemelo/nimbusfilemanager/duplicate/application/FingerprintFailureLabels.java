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

		return new FingerprintFailureResponse(failure.path(), reason.name(), label(reason), hint(reason), tone(reason),
				failure.error());
	}

	/**
	 * One colour per reason, so a long list separates by eye before it is read. The
	 * scale is the one the rest of the application already uses: red for the file
	 * that was lost, amber for a real image refused, blue for a known limitation,
	 * grey for what was never a photo.
	 */
	private String tone(FingerprintFailureReason reason) {
		return switch (reason) {
		case CORRUPTED_FILE -> "error";
		case DECODER_REFUSED -> "warn";
		case UNSUPPORTED_FORMAT -> "info";
		case NOT_AN_IMAGE -> "muted";
		case UNKNOWN -> "ok";
		};
	}

	/**
	 * What the label cannot fit: the cause, with the examples this library hit.
	 */
	private String hint(FingerprintFailureReason reason) {
		return message(switch (reason) {
		case CORRUPTED_FILE -> "backend.fingerprint.reason.corruptedFile.hint";
		case NOT_AN_IMAGE -> "backend.fingerprint.reason.notAnImage.hint";
		case UNSUPPORTED_FORMAT -> "backend.fingerprint.reason.unsupportedFormat.hint";
		case DECODER_REFUSED -> "backend.fingerprint.reason.decoderRefused.hint";
		case UNKNOWN -> "backend.fingerprint.reason.unknown.hint";
		});
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