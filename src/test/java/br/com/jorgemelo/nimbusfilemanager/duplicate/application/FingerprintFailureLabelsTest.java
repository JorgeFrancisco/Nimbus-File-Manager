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
	void aCorruptedFileIsTheOneFlaggedAsSevere() {
		FingerprintFailureResponse described = describe(FingerprintFailureReason.CORRUPTED_FILE);

		assertThat(described.severe()).isTrue();
		assertThat(described.reason()).isEqualTo("CORRUPTED_FILE");
		assertThat(described.reasonLabel()).isNotBlank().doesNotContain("CORRUPTED_FILE");
	}

	/**
	 * A format nothing decodes is a limitation, not a loss, and must not be dressed
	 * like one.
	 */
	@Test
	void aFormatNothingReadsIsNotSevere() {
		assertThat(describe(FingerprintFailureReason.UNSUPPORTED_FORMAT).severe()).isFalse();
		assertThat(describe(FingerprintFailureReason.DECODER_REFUSED).severe()).isFalse();
		assertThat(describe(FingerprintFailureReason.NOT_AN_IMAGE).severe()).isFalse();
		assertThat(describe(FingerprintFailureReason.UNKNOWN).severe()).isFalse();
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
		assertThat(described.severe()).isFalse();
	}

	private FingerprintFailureResponse describe(FingerprintFailureReason reason) {
		return labels.describe(List.of(new FingerprintFailureDetail("D:\\fotos\\a.jpg", reason, "invalid data")))
				.getFirst();
	}
}