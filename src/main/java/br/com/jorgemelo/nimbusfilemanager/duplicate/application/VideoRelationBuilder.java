package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoSignature;

/**
 * The video producer of relations: every pair the video comparison approves,
 * written down as {@code (first, second, score)} over candidate positions.
 *
 * <p>
 * It is the counterpart of {@link PhotoRelationBuilder} and it is the only place
 * where the two media differ. What is specific to video lives behind one call -
 * the duration and aspect gates, the frames aligned by {@code sampleIndex}, the
 * pHash radius, SSIM, the concordant-frame quorum and the trimmed mean are all
 * inside {@link VideoSimilarityAlgorithm#similarityPercent} - and what comes out
 * is the shared representation everything downstream already reads. Nothing
 * here is a second copy of a rule: the verdict is asked of the production
 * algorithm, exactly as the grouping asks for it today.
 *
 * <p>
 * <b>The bucket gate is part of the verdict, not an optimisation on top of
 * it.</b> Two videos within the duration tolerance always share a bucket, so for
 * videos that have a duration the gate only ever rejects pairs the comparison
 * would reject anyway. A video <em>without</em> a duration is the exception: it
 * buckets alone, while {@code durationCompatible} would have admitted it against
 * anything. Reproducing the gate here is therefore what makes this equivalent to
 * the path in use - leaving it out would relate pairs the current analysis never
 * relates, which is a behaviour change wearing the clothes of a refactor.
 *
 * <p>
 * Only approvals are produced, for the reason the shared storage exists: the
 * grouping asks one question - does this pair reach the threshold - and outside
 * the radius, scored below it, and never compared are one answer to it.
 *
 * <p>
 * The enumeration is the whole pair space, which is what a rebuild is. An
 * arrival differs only in which pairs it visits - the newcomers against the
 * covered set and against each other - and that is a condition on the loop, not
 * another comparison: {@link #relate} is what it would reuse unchanged.
 */
final class VideoRelationBuilder {

	/**
	 * Rows between progress reports. The callback writes to the database, so one
	 * report per candidate would cost more than the comparison it describes.
	 */
	private static final int ROWS_PER_REPORT = 64;

	private final VideoSimilarityAlgorithm algorithm;

	VideoRelationBuilder(VideoSimilarityAlgorithm algorithm) {
		this.algorithm = algorithm;
	}

	/**
	 * @param candidates the videos to relate, in the order the grouping will visit
	 * them - which is {@code catalog_file.id} ascending, because the placement is
	 * greedy and depends on it
	 * @param minimum the threshold this run is about; it decides both which frames
	 * count as concordant and whether the pair's trimmed mean is enough
	 */
	BuiltRelations build(List<VideoSignature> candidates, int minimum, SimilarityProgressCallback progress) {
		Map<UUID, Set<Long>> buckets = buckets(candidates);

		RelationAccumulator approved = new RelationAccumulator();

		progress.update(0, candidates.size());

		for (int left = 0; left < candidates.size(); left++) {
			for (int right = left + 1; right < candidates.size(); right++) {
				relate(candidates, buckets, approved, left, right, minimum);
			}

			report(left + 1, candidates.size(), progress);
		}

		return approved.toRelations(candidates.size());
	}

	/**
	 * One pair, through the gate and then through the comparison - which is what
	 * the grouping's scorer does today, and the reason this produces the same
	 * relations rather than merely similar ones.
	 */
	private void relate(List<VideoSignature> candidates, Map<UUID, Set<Long>> buckets, RelationAccumulator approved,
			int left, int right, int minimum) {
		VideoSignature first = candidates.get(left);
		VideoSignature second = candidates.get(right);

		if (Collections.disjoint(buckets.get(first.id()), buckets.get(second.id()))) {
			return;
		}

		int score = algorithm.similarityPercent(first, second, minimum);

		if (score >= minimum) {
			approved.approve(left, right, score);
		}
	}

	private Map<UUID, Set<Long>> buckets(List<VideoSignature> candidates) {
		Map<UUID, Set<Long>> buckets = new HashMap<>();

		for (VideoSignature candidate : candidates) {
			buckets.put(candidate.id(), algorithm.candidateBuckets(candidate));
		}

		return buckets;
	}

	private void report(int rowsDone, int total, SimilarityProgressCallback progress) {
		if (rowsDone % ROWS_PER_REPORT == 0 || rowsDone == total) {
			progress.update(rowsDone, total);
		}
	}
}