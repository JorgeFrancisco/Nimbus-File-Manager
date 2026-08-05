package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.RelationParameters;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoFrameHash;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoSignature;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoFrameRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoGateRow;

/**
 * The relations an arriving video creates, computed the way a rebuild computes
 * them and over the pairs an arrival actually changes.
 *
 * <p>
 * Same gates, same frames, same quorum and same trimmed mean as
 * {@link VideoRelationBuilder} - the verdict is asked of the production
 * algorithm on both paths, so the answer is the answer a rebuild would give.
 * What differs is which pairs are asked about and, following from that, what has
 * to be read to ask.
 *
 * <p>
 * <b>The load is in two steps, and that is the whole reason this class exists
 * rather than the rebuild being called with a shorter list.</b> A rebuild holds
 * every video's frames because every video is going to be compared against every
 * other. An arrival compares a handful of videos against the library: it needs
 * the duration and display size of every video - about twenty bytes each - and
 * it needs the frames, five kilobytes each, only for the videos a pair survived
 * the gates to name. On measured data the gates admit four pairs in a hundred,
 * so loading rows the rebuild's way would spend most of the cost of a rebuild's
 * read in order to avoid a rebuild. At a hundred thousand videos that is the
 * difference between reading a few megabytes and reading half a gigabyte.
 *
 * <p>
 * Old against old is never asked. Not as an optimisation but because the answer
 * cannot have changed: whether two videos look alike is a fact about the two of
 * them, and neither was touched. A pair whose verdict could have changed has a
 * re-fingerprinted video in it - or one whose duration or display size was read
 * again - and such a video is not covered, because both its relations and its
 * coverage were forgotten together.
 */
final class VideoArrivalRelationBuilder {

	private final MediaFingerprintRepository mediaFingerprintRepository;
	private final VideoSimilarityAlgorithm algorithm;

	VideoArrivalRelationBuilder(MediaFingerprintRepository mediaFingerprintRepository,
			VideoSimilarityAlgorithm algorithm) {
		this.mediaFingerprintRepository = mediaFingerprintRepository;
		this.algorithm = algorithm;
	}

	/**
	 * @param newcomers the eligible videos not yet incorporated, ascending
	 * @param covered every video already incorporated, ascending - not the eligible
	 * ones, which is the point the coverage model turns on
	 */
	ArrivingRelations build(RelationParameters parameters, List<Long> newcomers, List<Long> covered, int minimum,
			SimilarityProgressCallback progress) {
		long[] arrivals = ascending(newcomers);
		long[] wanted = merged(arrivals, ascending(covered));

		List<VideoSignature> gates = new ArrayList<>();
		List<Long> ids = new ArrayList<>();

		// The rows arrive in catalog_file.id order, which is the order the greedy
		// placement depends on, so keeping the ones this run is about preserves it
		// without a sort.
		for (VideoGateRow row : mediaFingerprintRepository.findVideoGateRows(FingerprintKind.VIDEO_PHASH.name(),
				parameters.algorithmId())) {
			if (Arrays.binarySearch(wanted, row.getCatalogFileId()) >= 0) {
				ids.add(row.getCatalogFileId());
				gates.add(gateSignature(row));
			}
		}

		boolean[] newcomer = newcomers(ids, arrivals);

		long[] pairs = surviving(gates, newcomer, progress);

		List<VideoSignature> signatures = withFrames(parameters, ids, gates, pairs);

		BuiltRelations built = approve(signatures, pairs, minimum, progress);

		return new ArrivingRelations(built, toArray(ids), incorporated(ids, newcomer), pairs.length,
				loaded(signatures));
	}

	/**
	 * A signature carrying only what the gates read. It has no frames, which is
	 * legitimate: nothing looks at them until the pair has survived.
	 */
	private VideoSignature gateSignature(VideoGateRow row) {
		return new VideoSignature(new UUID(0, row.getCatalogFileId()), List.of(), row.getDurationSeconds(),
				row.getDisplayWidth(), row.getDisplayHeight());
	}

	/**
	 * The pairs an arrival creates that the cheap signals do not already refuse:
	 * each newcomer against every covered video, plus the newcomers against each
	 * other, and never a pair of two covered ones.
	 */
	private long[] surviving(List<VideoSignature> gates, boolean[] newcomer, SimilarityProgressCallback progress) {
		int count = gates.size();

		long[] pairs = new long[1 << 12];
		int found = 0;
		int done = 0;

		for (int left = 0; left < count; left++) {
			if (!newcomer[left]) {
				continue;
			}

			for (int right = 0; right < count; right++) {
				if (ArrivalPairs.alreadyTaken(newcomer, left, right)
						|| !sharesABucket(gates.get(left), gates.get(right))
						|| !algorithm.gatesAllow(gates.get(left), gates.get(right))) {
					continue;
				}

				pairs = ArrivalPairs.withRoomFor(pairs, found);

				pairs[found++] = ArrivalPairs.pack(left, right);
			}

			progress.update(++done, count);
		}

		return ArrivalPairs.sorted(pairs, found);
	}

	/**
	 * The signatures of the videos a surviving pair named, and only those. A video
	 * no pair reached keeps the frameless signature it was gated with, which no
	 * comparison will ever be asked about.
	 */
	private List<VideoSignature> withFrames(RelationParameters parameters, List<Long> ids, List<VideoSignature> gates,
			long[] pairs) {
		boolean[] needed = new boolean[gates.size()];

		for (long pair : pairs) {
			needed[ArrivalPairs.left(pair)] = true;
			needed[ArrivalPairs.right(pair)] = true;
		}

		Map<Long, List<VideoFrameHash>> frames = framesOf(parameters, ids, needed);

		List<VideoSignature> signatures = new ArrayList<>(gates.size());

		for (int index = 0; index < gates.size(); index++) {
			VideoSignature gate = gates.get(index);

			signatures.add(new VideoSignature(gate.id(),
					frames.getOrDefault(ids.get(index), List.<VideoFrameHash>of()), gate.durationSeconds(),
					gate.width(), gate.height()));
		}

		return signatures;
	}

	private Map<Long, List<VideoFrameHash>> framesOf(RelationParameters parameters, List<Long> ids, boolean[] needed) {
		Long[] wanted = wanted(ids, needed);

		Map<Long, List<VideoFrameHash>> frames = new HashMap<>();

		if (wanted.length == 0) {
			return frames;
		}

		for (VideoFrameRow row : mediaFingerprintRepository.findVideoFrames(FingerprintKind.VIDEO_PHASH.name(),
				parameters.algorithmId(), wanted)) {
			frames.computeIfAbsent(row.getCatalogFileId(), _ -> new ArrayList<>())
					.add(new VideoFrameHash(row.getSampleIndex(), row.getHashBytes(), row.getSampleBytes()));
		}

		return frames;
	}

	/**
	 * The comparison itself, over the pairs that survived and with the frames now
	 * in hand. A pair naming a video whose frames went away between the two queries
	 * simply scores nothing - the algorithm finds no aligned frame - which is the
	 * same outcome as never having been asked.
	 */
	private BuiltRelations approve(List<VideoSignature> signatures, long[] pairs, int minimum,
			SimilarityProgressCallback progress) {
		RelationAccumulator approved = new RelationAccumulator();

		for (int index = 0; index < pairs.length; index++) {
			int left = ArrivalPairs.left(pairs[index]);
			int right = ArrivalPairs.right(pairs[index]);

			int score = algorithm.similarityPercent(signatures.get(left), signatures.get(right), minimum);

			if (score >= minimum) {
				approved.approve(left, right, score);
			}

			report(progress, index, pairs.length);
		}

		return approved.toRelations(signatures.size());
	}

	/**
	 * Reported per thousand pairs rather than per pair: the callback writes
	 * progress to the database, and a comparison is microseconds.
	 */
	private void report(SimilarityProgressCallback progress, int index, int total) {
		if (index % 1000 == 0 || index == total - 1) {
			progress.update(index + 1, total);
		}
	}

	private boolean sharesABucket(VideoSignature first, VideoSignature second) {
		return !Collections.disjoint(algorithm.candidateBuckets(first), algorithm.candidateBuckets(second));
	}

	private boolean[] newcomers(List<Long> ids, long[] arrivals) {
		boolean[] newcomer = new boolean[ids.size()];

		for (int index = 0; index < ids.size(); index++) {
			newcomer[index] = Arrays.binarySearch(arrivals, ids.get(index)) >= 0;
		}

		return newcomer;
	}

	/**
	 * The arrivals this run actually had a row for, which is what it may claim as
	 * incorporated.
	 *
	 * <p>
	 * Derived from what was loaded rather than from what was asked for. A video
	 * whose fingerprint went away between the two queries was compared against
	 * nothing, and marking it covered would state that every pair it takes part in
	 * has been evaluated - the one claim this table is not allowed to get wrong.
	 */
	private long[] incorporated(List<Long> ids, boolean[] newcomer) {
		long[] incorporated = new long[count(newcomer)];
		int position = 0;

		for (int index = 0; index < ids.size(); index++) {
			if (newcomer[index]) {
				incorporated[position++] = ids.get(index);
			}
		}

		return incorporated;
	}

	private Long[] wanted(List<Long> ids, boolean[] needed) {
		Long[] wanted = new Long[count(needed)];
		int position = 0;

		for (int index = 0; index < ids.size(); index++) {
			if (needed[index]) {
				wanted[position++] = ids.get(index);
			}
		}

		return wanted;
	}

	private int count(boolean[] flags) {
		int count = 0;

		for (boolean flag : flags) {
			if (flag) {
				count++;
			}
		}

		return count;
	}

	private int loaded(List<VideoSignature> signatures) {
		int loaded = 0;

		for (VideoSignature signature : signatures) {
			if (!signature.frames().isEmpty()) {
				loaded++;
			}
		}

		return loaded;
	}

	private long[] toArray(List<Long> ids) {
		long[] array = new long[ids.size()];

		for (int index = 0; index < ids.size(); index++) {
			array[index] = ids.get(index);
		}

		return array;
	}

	private long[] ascending(List<Long> ids) {
		long[] ascending = toArray(ids);

		Arrays.sort(ascending);

		return ascending;
	}

	/**
	 * The two sets as one sorted array with no repetition. They are disjoint by
	 * definition - a covered video is not a newcomer - but a run that overlapped
	 * them would silently compare a video with itself, so the merge removes
	 * duplicates rather than assuming there are none.
	 */
	private long[] merged(long[] first, long[] second) {
		long[] merged = new long[first.length + second.length];

		System.arraycopy(first, 0, merged, 0, first.length);
		System.arraycopy(second, 0, merged, first.length, second.length);

		Arrays.sort(merged);

		int distinct = 0;

		for (int index = 0; index < merged.length; index++) {
			if (index == 0 || merged[index] != merged[index - 1]) {
				merged[distinct++] = merged[index];
			}
		}

		return Arrays.copyOf(merged, distinct);
	}

}