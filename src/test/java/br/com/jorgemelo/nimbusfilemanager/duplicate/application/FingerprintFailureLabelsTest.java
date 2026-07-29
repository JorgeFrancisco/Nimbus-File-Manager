package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintFailureResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.FingerprintFailureDetail;

/**
 * The screen must not turn a code into a sentence, nor decide on its own which
 * failure deserves attention - both arrive decided.
 */
class FingerprintFailureLabelsTest {

	private final FingerprintFailureLabels labels = new FingerprintFailureLabels();

	@Test
	void aCorruptedFileReadsAsWordsAndNotAsItsCode() {
		FingerprintFailureResponse described = describe(FingerprintFailureReason.CORRUPTED_FILE);

		assertThat(described.reason()).isEqualTo("CORRUPTED_FILE");
		assertThat(described.reasonLabel()).isNotBlank().doesNotContain("CORRUPTED_FILE");
	}

	/** Colour helps read the list, and is decided here, not in the browser. */
	@Test
	void eachReasonWearsItsOwnColour() {
		assertThat(describe(FingerprintFailureReason.CORRUPTED_FILE).tone()).isEqualTo("error");
		assertThat(describe(FingerprintFailureReason.DECODER_REFUSED).tone()).isEqualTo("warn");
		assertThat(describe(FingerprintFailureReason.UNSUPPORTED_FORMAT).tone()).isEqualTo("info");
		assertThat(describe(FingerprintFailureReason.NOT_AN_IMAGE).tone()).isEqualTo("muted");
		assertThat(describe(FingerprintFailureReason.UNKNOWN).tone()).isEqualTo("ok");
	}

	/** The hint carries the examples the badge has no room for. */
	@Test
	void everyReasonExplainsItselfWithExamples() {
		for (FingerprintFailureReason reason : FingerprintFailureReason.values()) {
			assertThat(describe(reason).reasonHint()).as("hint of %s", reason).isNotBlank();
		}
	}

	@Test
	void everyReasonReadsAsWordsAndKeepsItsPathAndError() {
		for (FingerprintFailureReason reason : FingerprintFailureReason.values()) {
			FingerprintFailureResponse described = describe(reason);

			assertThat(described.reasonLabel()).as("label of %s", reason).isNotBlank();
			assertThat(described.path()).isEqualTo("D:\\fotos\\a.jpg");
			assertThat(described.error()).isEqualTo("invalid data");
		}
	}

	/** A row written before the reason existed still has to render. */
	@Test
	void aFailureWithoutAReasonReadsAsUnclassified() {
		FingerprintFailureResponse described = describe(null);

		assertThat(described.reason()).isEqualTo("UNKNOWN");
		assertThat(described.tone()).isEqualTo("ok");
	}

	private FingerprintFailureResponse describe(FingerprintFailureReason reason) {
		return labels.describe(List.of(new FingerprintFailureDetail("D:\\fotos\\a.jpg", reason, "invalid data")))
				.getFirst();
	}
}