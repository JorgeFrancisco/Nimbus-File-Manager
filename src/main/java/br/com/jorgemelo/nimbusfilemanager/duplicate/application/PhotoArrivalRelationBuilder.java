package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.RelationParameters;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoSampleRow;

/**
 * The relations an arrival creates, computed the way a rebuild computes them
 * and over the pairs an arrival actually changes.
 *
 * <p>
 * Same two filters, same order, same thresholds as {@link PhotoRelationBuilder}
 * - distance first, SSIM only inside the radius - because the answer has to be
 * the answer a rebuild would give. What differs is which pairs are asked about
 * and, following from that, what has to be read to ask.
 *
 * <p>
 * <b>The load is in two steps, and that is the whole reason this class exists
 * rather than the rebuild being called with a shorter list.</b> A rebuild holds
 * one row per photo carrying both the hash and the sample, which is right when
 * every photo is going to be compared against every other. An arrival compares
 * a handful of photos against the library: it needs every hash - 32 bytes each,
 * under 4 MB for a real library - and it needs the samples, 1 KB each and 120 MB
 * for the same library, only for the pairs the distance filter let through,
 * which on measured data is under one in a thousand. Loading rows the rebuild's
 * way would spend the whole cost of a rebuild's read to avoid a rebuild.
 *
 * <p>
 * The hashes are read without a lifecycle filter and without naming the files.
 * Both are deliberate and both are explained where the queries are declared: the
 * covered set is the size of the library, so it cannot be an {@code IN} list;
 * and a covered file hidden today is still part of the relation universe, so
 * filtering it out would leave the pair between it and the newcomer evaluated by
 * nobody.
 */
final class PhotoArrivalRelationBuilder {

	private static final int LONGS_PER_HASH = 4;

	private final MediaFingerprintRepository mediaFingerprintRepository;
	private final LuminanceSsimService luminanceSsimService;
	private final int radius;

	PhotoArrivalRelationBuilder(MediaFingerprintRepository mediaFingerprintRepository,
			LuminanceSsimService luminanceSsimService, int radius) {
		this.mediaFingerprintRepository = mediaFingerprintRepository;
		this.luminanceSsimService = luminanceSsimService;
		this.radius = radius;
	}

	/**
	 * @param newcomers the eligible files not yet incorporated, ascending
	 * @param covered every file already incorporated, ascending - not the eligible
	 * ones, which is the point the coverage model turns on
	 */
	ArrivingRelations build(RelationParameters parameters, List<Long> newcomers, List<Long> covered, int minimum,
			SimilarityProgressCallback progress) {
		long[] arrivals = ascending(newcomers);
		long[] wanted = merged(arrivals, ascending(covered));

		long[] ids = new long[wanted.length];
		long[] packed = new long[wanted.length * LONGS_PER_HASH];
		boolean[] newcomer = new boolean[wanted.length];
		int count = 0;
		int arrived = 0;

		// The rows arrive in catalog_file.id order, which is the order the greedy
		// placement depends on, so keeping the ones this run is about preserves it
		// without a sort.
		for (PhotoHashRow row : mediaFingerprintRepository.findPhotoHashes(FingerprintKind.PHOTO_PHASH.name(),
				parameters.algorithmId())) {
			long catalogFileId = row.getCatalogFileId();

			if (Arrays.binarySearch(wanted, catalogFileId) < 0) {
				continue;
			}

			ids[count] = catalogFileId;
			newcomer[count] = Arrays.binarySearch(arrivals, catalogFileId) >= 0;

			PhotoRelationBuilder.pack(row.getHashBytes(), packed, count);

			if (newcomer[count]) {
				arrived++;
			}

			count++;
		}

		long[] pairs = ArrivingPairs.withinRadius(packed, newcomer, count, radius, arrived,
				(done, total) -> PhotoRelationBuilder.reportDistance(done, total, progress));

		byte[][] luminance = samples(parameters, ids, count, pairs);

		BuiltRelations built = new PhotoRelationBuilder(luminanceSsimService, radius).approve(luminance, pairs, minimum,
				arrived, progress);

		return new ArrivingRelations(built, Arrays.copyOf(ids, count), incorporated(ids, newcomer, count, arrived),
				pairs.length, loaded(luminance));
	}

	/**
	 * The samples of the photos a pair survived to need, and no others.
	 *
	 * <p>
	 * Positions the distance filter rejected keep a {@code null}, which is what a
	 * missing sample has always meant to the SSIM pass - so a photo whose sample
	 * was never loaded and one whose sample does not exist are the same thing to
	 * it, and neither produces a relation.
	 */
	private byte[][] samples(RelationParameters parameters, long[] ids, int count, long[] pairs) {
		boolean[] needed = new boolean[count];

		for (long pair : pairs) {
			needed[(int) (pair >>> 32)] = true;
			needed[(int) pair] = true;
		}

		byte[][] luminance = new byte[count][];

		Long[] wanted = wanted(ids, needed, count);

		if (wanted.length == 0) {
			return luminance;
		}

		Map<Long, byte[]> byCatalogFileId = new HashMap<>();

		for (PhotoSampleRow row : mediaFingerprintRepository.findPhotoSamples(FingerprintKind.PHOTO_PHASH.name(),
				parameters.algorithmId(), wanted)) {
			byCatalogFileId.put(row.getCatalogFileId(), row.getSampleBytes());
		}

		for (int index = 0; index < count; index++) {
			if (needed[index]) {
				luminance[index] = byCatalogFileId.get(ids[index]);
			}
		}

		return luminance;
	}

	private Long[] wanted(long[] ids, boolean[] needed, int count) {
		int size = 0;

		for (int index = 0; index < count; index++) {
			if (needed[index]) {
				size++;
			}
		}

		Long[] wanted = new Long[size];
		int position = 0;

		for (int index = 0; index < count; index++) {
			if (needed[index]) {
				wanted[position++] = ids[index];
			}
		}

		return wanted;
	}

	/**
	 * The arrivals this run actually had a hash for, which is what it may claim as
	 * incorporated.
	 *
	 * <p>
	 * Derived from what was loaded rather than from what was asked for. A file
	 * whose fingerprint row went away between the two queries was compared against
	 * nothing, and marking it covered would state that every pair it takes part in
	 * has been evaluated - the one claim this table is not allowed to get wrong.
	 */
	private long[] incorporated(long[] ids, boolean[] newcomer, int count, int arrived) {
		long[] incorporated = new long[arrived];
		int position = 0;

		for (int index = 0; index < count; index++) {
			if (newcomer[index]) {
				incorporated[position++] = ids[index];
			}
		}

		return incorporated;
	}

	private int loaded(byte[][] luminance) {
		int loaded = 0;

		for (byte[] sample : luminance) {
			if (sample != null) {
				loaded++;
			}
		}

		return loaded;
	}

	private long[] ascending(List<Long> ids) {
		long[] ascending = new long[ids.size()];

		for (int index = 0; index < ids.size(); index++) {
			ascending[index] = ids.get(index);
		}

		Arrays.sort(ascending);

		return ascending;
	}

	/**
	 * The two sets as one sorted array with no repetition. They are disjoint by
	 * definition - a covered file is not a newcomer - but a run that overlapped
	 * them would silently compare a file with itself, so the merge removes
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