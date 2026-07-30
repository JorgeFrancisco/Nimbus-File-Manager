package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataConstants;

/**
 * The samples reach the codec from ffmpeg and the hashes from the database, so
 * both sizes come from outside: a sample of the wrong size would still hash and
 * a truncated hash would still compare, silently answering nonsense about which
 * photos are duplicates. Both are refused instead.
 */
class PerceptualHashCodecTest {

	@Test
	void hashingRefusesASampleThatIsNotThirtyTwoBySquare() {
		byte[] truncated = new byte[MetadataConstants.SAMPLE_BYTES - 1];

		assertThatThrownBy(() -> PerceptualHashCodec.hash256(truncated))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> PerceptualHashCodec.hash256(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void distanceRefusesAHashThatIsNotThirtyTwoBytes() {
		byte[] complete = new byte[MetadataConstants.HASH_BYTES];
		byte[] truncated = new byte[MetadataConstants.HASH_BYTES - 1];

		assertThatThrownBy(() -> PerceptualHashCodec.distance(complete, truncated))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> PerceptualHashCodec.distance(null, complete))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aHashIsAtNoDistanceFromItself() {
		byte[] hash = PerceptualHashCodec.hash256(new byte[MetadataConstants.SAMPLE_BYTES]);

		assertThat(PerceptualHashCodec.distance(hash, hash.clone())).isZero();
	}
}