package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRawResponse;

/**
 * The two passes that turn photos into approved pairs.
 *
 * <p>
 * Both keep their results in arrays that start small and double, which is the
 * only way a million candidate pairs is affordable - and also the only place
 * this class can lose a pair without anybody noticing. A library where the
 * buffer never grows and one where it grows produce the same kind of answer, so
 * the growth is exercised here rather than left to the first real run that
 * happens to be big enough.
 *
 * <p>
 * The sizes are chosen to cross the thresholds and no more: 363 photos make
 * 65.703 pairs against a buffer of 65.536, and 182 make 16.471 approvals against
 * one of 16.384.
 */
class PhotoRelationBuilderTest {

	private static final int RADIUS = 96;
	private static final int MINIMUM = 95;

	private static final SimilarityProgressCallback SILENT = (_, _) -> {
	};

	private final PhotoRelationBuilder builder = new PhotoRelationBuilder(new LuminanceSsimService(), RADIUS);

	/**
	 * Every pair survives the distance scan and the buffer has to grow to hold
	 * them, in the order the greedy grouping depends on.
	 */
	@Test
	void theCandidateBufferGrowsWithoutLosingOrReorderingAPair() {
		List<PhotoHashRawResponse> candidates = alike(363);

		long[] pairs = scan(candidates);

		assertThat(pairs).hasSize(363 * 362 / 2);

		assertThat((int) (pairs[0] >>> 32)).isZero();
		assertThat((int) pairs[0]).isEqualTo(1);

		assertThat((int) (pairs[pairs.length - 1] >>> 32)).isEqualTo(361);
		assertThat((int) pairs[pairs.length - 1]).isEqualTo(362);
	}

	/** And the same for the three arrays the approvals go into. */
	@Test
	void theApprovalBuffersGrowWithoutLosingARelation() {
		List<PhotoHashRawResponse> candidates = alike(182);

		BuiltRelations built = builder.approve(candidates, scan(candidates), MINIMUM, SILENT);

		assertThat(built.count()).isEqualTo(182 * 181 / 2);
		assertThat(built.scores()[built.count() - 1]).isEqualTo(100);
		assertThat(built.relations().approved(0, 181)).isTrue();
	}

	/**
	 * A photo whose sample was never stored is skipped instead of compared. It has
	 * a hash, so the distance scan offers it as a candidate; SSIM has nothing to
	 * work with, and inventing an answer would be worse than having none.
	 */
	@Test
	void aCandidateWithoutASampleIsSkippedRatherThanScored() {
		List<PhotoHashRawResponse> candidates = List.of(photo(0L, sample()), photo(1L, null), photo(2L, sample()));

		BuiltRelations built = builder.approve(candidates, scan(candidates), MINIMUM, SILENT);

		assertThat(built.count()).isEqualTo(1);
		assertThat(built.first()[0]).isZero();
		assertThat(built.second()[0]).isEqualTo(2);
	}

	/** Below the threshold is not an approval, and leaves no relation behind. */
	@Test
	void aPairThatScoresBelowTheThresholdIsNotKept() {
		List<PhotoHashRawResponse> candidates = List.of(photo(0L, sample()), photo(1L, contrastingSample()));

		BuiltRelations built = builder.approve(candidates, scan(candidates), MINIMUM, SILENT);

		assertThat(built.count()).isZero();
		assertThat(built.relations().approved(0, 1)).isFalse();
	}

	private long[] scan(List<PhotoHashRawResponse> candidates) {
		return PhotoRelationBuilder.withinRadius(PhotoRelationBuilder.pack(candidates), candidates.size(), RADIUS,
				SILENT);
	}

	/** Photos with the same hash and the same sample: every pair is approved. */
	private List<PhotoHashRawResponse> alike(int count) {
		List<PhotoHashRawResponse> candidates = new ArrayList<>(count);

		for (int index = 0; index < count; index++) {
			candidates.add(photo((long) index, sample()));
		}

		return candidates;
	}

	private PhotoHashRawResponse photo(Long id, byte[] luminance) {
		return new PhotoHashRawResponse(id, new byte[32], luminance, id + ".jpg", "jpg", 100L,
				"C:/Fotos/" + id + ".jpg", "C:/Fotos", Instant.parse("2024-01-01T10:00:00Z"));
	}

	private byte[] sample() {
		byte[] luminance = new byte[1024];

		Arrays.fill(luminance, (byte) 120);

		return luminance;
	}

	/**
	 * A sample that shares no structure with the flat one: alternating extremes,
	 * which SSIM scores nowhere near the threshold.
	 */
	private byte[] contrastingSample() {
		byte[] luminance = new byte[1024];

		for (int index = 0; index < luminance.length; index++) {
			luminance[index] = (byte) (index % 2 == 0 ? 0 : 255);
		}

		return luminance;
	}
}