package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaQualityRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.CompositionRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoSampleRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityRelationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

/**
 * A library of photos and the two tables the analysis reads and writes, kept in
 * memory and behaving the way the database behaves.
 *
 * <p>
 * It exists so that a rebuild and a sequence of arrivals can be run through the
 * <em>production</em> code and their answers compared. Asserting an arrival
 * against a hand-written expectation would only check that the expectation and
 * the code were written by the same person on the same afternoon; asserting it
 * against the rebuild checks the claim the whole design rests on.
 *
 * <p>
 * The parts that are faked are the ones a database would provide - which rows
 * come back for a query, what an upsert does to a key that is already there -
 * and nothing else. The distance scan, SSIM, the placement and the coverage
 * bookkeeping are the real classes: what is being tested is exactly the code
 * that would run.
 *
 * <p>
 * Two behaviours are worth naming because they are the ones the arrival path
 * turns on. The hash query returns every fingerprinted photo, eligible or not,
 * because a covered file that is hidden today is still part of the relation
 * universe. And an upsert overwrites the score of a key it already holds,
 * because a re-fingerprinted photo scores differently against a neighbour it
 * kept.
 */
final class PhotoSimilarityLibrary {

	private static final int SAMPLE_BYTES = 1024;
	private static final int HASH_BYTES = 32;

	private final Map<Long, PhotoHashRawResponse> photos = new TreeMap<>();
	private final Set<Long> eligible = new TreeSet<>();

	/** The two relation tables, shared with the video harness - see {@link RelationStore}. */
	private final RelationStore store = new RelationStore();

	private final MediaFingerprintRepository fingerprints = mock(MediaFingerprintRepository.class);
	private final MediaQualityRepository mediaQualityRepository = mock(MediaQualityRepository.class);
	private final DuplicateExclusionService exclusions = mock(DuplicateExclusionService.class);

	private final PhotoSimilarityService service;

	PhotoSimilarityLibrary() {
		wireReads();

		when(exclusions.signature()).thenReturn("none");
		when(mediaQualityRepository.findByPublicIdIn(any())).thenReturn(List.of());

		service = new PhotoSimilarityService(fingerprints,
				new DuplicateGroupAssembler(new DuplicateKeepPolicy(), mediaQualityRepository), new LuminanceSsimService(),
				exclusions, store.writer(), store.repository());
	}

	PhotoSimilarityService service() {
		return service;
	}

	/**
	 * The writer, so a test can ask <em>which</em> route a run took: a rebuild
	 * replaces the family's relations, an arrival adds to them, and the two are
	 * different methods.
	 */
	SimilarityRelationWriter writer() {
		return store.writer();
	}

	/**
	 * A photo the analysis can see. Eligible from the moment it exists, which is
	 * what having a fingerprint and no exclusion means.
	 */
	void photo(long catalogFileId, byte[] hash, byte[] sample) {
		photos.put(catalogFileId, new PhotoHashRawResponse(catalogFileId, hash, sample, catalogFileId + ".jpg", "jpg",
				100L, "C:/Fotos/" + catalogFileId + ".jpg", "C:/Fotos",
				LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0)));

		eligible.add(catalogFileId);
	}

	/**
	 * The file stops being eligible without stopping being a file - what an
	 * exclusion, a quarantine or a logical deletion does. Its fingerprint stays,
	 * and so does its coverage.
	 */
	void hide(long catalogFileId) {
		eligible.remove(catalogFileId);
	}

	void show(long catalogFileId) {
		eligible.add(catalogFileId);
	}

	/** The file is gone for good: the catalog row and all that cascades from it. */
	void purge(long catalogFileId) {
		photos.remove(catalogFileId);

		eligible.remove(catalogFileId);

		store.purge(catalogFileId);
	}

	/**
	 * The photo is fingerprinted again: same catalog id, different image. The
	 * production path forgets what was computed from the old one, which is what
	 * puts it back among the files nobody has compared.
	 */
	void refingerprint(long catalogFileId, byte[] hash, byte[] sample) {
		photo(catalogFileId, hash, sample);

		store.writer().forget(DuplicateConstants.ALGORITHM, catalogFileId);
	}

	/**
	 * The same photos and the same eligibility, with nothing computed yet.
	 *
	 * <p>
	 * What a full rebuild is run over, so that an arrival's answer can be compared
	 * against the answer to the same question rather than against a written-down
	 * expectation - which would only say that the test and the code agree.
	 */
	PhotoSimilarityLibrary copy() {
		PhotoSimilarityLibrary copy = new PhotoSimilarityLibrary();

		copy.photos.putAll(photos);
		copy.eligible.addAll(eligible);

		return copy;
	}

	int relationCount() {
		return store.relationCount();
	}

	Set<Long> covered() {
		return store.covered();
	}

	/** Every pair the relation table holds, as "smaller-larger", for assertions. */
	Set<String> approvedPairs() {
		return store.approvedPairs();
	}

	private void wireReads() {
		// A null limit is the absence of one, exactly as PostgreSQL reads LIMIT NULL -
		// and any() rather than anyInt() because the latter would not match it.
		when(fingerprints.findEligibleForSimilarity(any(), any())).thenAnswer(_ -> eligible.stream().toList());

		when(fingerprints.countEligibleForSimilarity(any(), any())).thenAnswer(_ -> eligible.size());

		when(fingerprints.findFingerprintedPhotos(any(), any(), any())).thenAnswer(call -> {
			Set<Long> wanted = Arrays.stream(call.<Long[]>getArgument(2)).collect(Collectors.toSet());

			return photos.values().stream().filter(photo -> wanted.contains(photo.catalogFileId())).toList();
		});

		when(fingerprints.findPhotoCompositionRows(any(), any(), any())).thenAnswer(call -> {
			Set<Long> wanted = Arrays.stream(call.<Long[]>getArgument(2)).collect(Collectors.toSet());

			return photos.values().stream().filter(photo -> wanted.contains(photo.catalogFileId()))
					.map(photo -> new CompositionRow(photo.id(), photo.currentFolder())).toList();
		});

		// Every fingerprinted photo, eligible or not - the difference the coverage
		// model turns on.
		when(fingerprints.findPhotoHashes(any(), any())).thenAnswer(_ -> photos.values().stream()
				.map(photo -> (PhotoHashRow) new StoredPhotoHash(photo.catalogFileId(), photo.phash())).toList());

		when(fingerprints.findPhotoSamples(any(), any(), any())).thenAnswer(call -> {
			Set<Long> wanted = Set.of((Long[]) call.getArgument(2));

			return photos.values().stream().filter(photo -> wanted.contains(photo.catalogFileId()))
					.map(photo -> (PhotoSampleRow) new StoredPhotoSample(photo.catalogFileId(), photo.luminance()))
					.toList();
		});
	}

	/**
	 * A hash whose distance to another built from the same seed is small, and to
	 * one built from a different seed is not. The bits are what the distance scan
	 * reads and nothing else, so a synthetic hash is as real as a computed one.
	 */
	static byte[] hash(long seed, int flippedBits) {
		byte[] hash = new byte[HASH_BYTES];

		Random random = new Random(seed);

		random.nextBytes(hash);

		Random flips = new Random(seed * 31 + flippedBits);

		for (int bit = 0; bit < flippedBits; bit++) {
			int position = flips.nextInt(HASH_BYTES * 8);

			hash[position / 8] ^= (byte) (1 << (position % 8));
		}

		return hash;
	}

	/**
	 * A 32x32 luminance sample. Photos of the same seed differ by a constant
	 * offset, which SSIM reads as a change of brightness and scores high; photos
	 * of different seeds are unrelated noise and score low.
	 */
	static byte[] sample(long seed, int offset) {
		byte[] sample = new byte[SAMPLE_BYTES];

		Random random = new Random(seed);

		for (int index = 0; index < SAMPLE_BYTES; index++) {
			sample[index] = (byte) Math.clamp(random.nextInt(200) + offset, 0, 255);
		}

		return sample;
	}

	/**
	 * A sample part way between two others, pixel by pixel.
	 *
	 * <p>
	 * What it buys is a similarity that is <em>not</em> transitive, which no
	 * amount of brightness shifting can produce: a photo three quarters of the way
	 * towards another scores well above the threshold against it and well below
	 * against the far end. That is the shape complete linkage exists to refuse, so
	 * a test of it needs data that has the shape.
	 *
	 * @param towardsFirst how much of the first sample survives, 0..1
	 */
	static byte[] blended(byte[] first, byte[] second, double towardsFirst) {
		byte[] blended = new byte[SAMPLE_BYTES];

		for (int index = 0; index < SAMPLE_BYTES; index++) {
			blended[index] = (byte) Math.round((first[index] & 0xFF) * towardsFirst
					+ (second[index] & 0xFF) * (1 - towardsFirst));
		}

		return blended;
	}

	static UUID publicId(long catalogFileId) {
		return UuidV7.fromLegacy(catalogFileId);
	}
}