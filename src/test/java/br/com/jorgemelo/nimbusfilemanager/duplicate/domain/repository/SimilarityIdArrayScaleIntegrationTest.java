package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.CompositionRow;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Video;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * The four queries that name a library's files can be handed a whole library.
 *
 * <p>
 * They used to name the files one bind parameter each, and PostgreSQL's wire
 * protocol carries at most 65.535 of them. A real library of 119.870 eligible
 * photos therefore asked for 119.872 - the ids plus the kind and the algorithm -
 * and the driver refused to prepare the statement before it ever reached the
 * database. Similarity analysis simply stopped working above a size this
 * product treats as ordinary.
 *
 * <p>
 * They now bind the ids as <em>one</em> array parameter, matched with
 * {@code array_contains}. This test is the structural proof: the array below is
 * nearly twice the protocol's ceiling, so if a single placeholder per element
 * had survived anywhere, preparing the statement would throw rather than return
 * rows. It is the mechanism that is under test, not the volume - the rows that
 * come back are a handful, and the other ~120.000 ids exist nowhere, which is
 * also the answer to what happens when an id names nothing.
 *
 * <p>
 * Against a real PostgreSQL because that is the whole point: the limit belongs
 * to the driver and the protocol, and no mock has one.
 */
class SimilarityIdArrayScaleIntegrationTest extends SharedPostgresIntegrationTest {

	private static final String PHOTO_ALGORITHM = "DCT_PHASH_256_V1";
	private static final String VIDEO_ALGORITHM = "FFMPEG_VIDEO_PHASH_V1";

	/**
	 * Comfortably past the 65.535 the protocol allows, and the order of magnitude
	 * of the library that failed.
	 */
	private static final int BEYOND_THE_PROTOCOL_CEILING = 120_000;

	private static final int PHOTOS = 3;
	private static final int VIDEOS = 2;
	private static final int FRAMES_PER_VIDEO = 2;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private MediaFingerprintRepository mediaFingerprintRepository;

	@Autowired
	private MediaQualityRepository mediaQualityRepository;

	@Test
	void namesMorePhotosThanTheWireProtocolCouldEverHaveCarried() {
		List<Long> photos = givenFingerprintedPhotos();

		Long[] ids = paddedTo(BEYOND_THE_PROTOCOL_CEILING, photos);

		Assertions.assertThat(ids).hasSizeGreaterThan(65_535);

		List<CompositionRow> composition = mediaFingerprintRepository
				.findPhotoCompositionRows(FingerprintKind.PHOTO_PHASH, PHOTO_ALGORITHM, ids);

		Assertions.assertThat(composition).as("the files that exist, and only those").hasSize(PHOTOS);
		Assertions.assertThat(mediaFingerprintRepository.findFingerprintedPhotos(FingerprintKind.PHOTO_PHASH,
				PHOTO_ALGORITHM, ids)).hasSize(PHOTOS);
	}

	@Test
	void namesMoreVideosThanTheWireProtocolCouldEverHaveCarried() {
		List<Long> videos = givenFingerprintedVideos();

		Long[] ids = paddedTo(BEYOND_THE_PROTOCOL_CEILING, videos);

		Assertions.assertThat(mediaFingerprintRepository.findVideoCompositionRows(FingerprintKind.VIDEO_PHASH,
				VIDEO_ALGORITHM, ids)).as("one row per sampled frame, as the heavy query returns")
				.hasSize(VIDEOS * FRAMES_PER_VIDEO);
		Assertions.assertThat(mediaFingerprintRepository.findFingerprintedVideoFrames(FingerprintKind.VIDEO_PHASH,
				VIDEO_ALGORITHM, ids)).hasSize(VIDEOS * FRAMES_PER_VIDEO);
	}

	/**
	 * An id named twice is a file returned once: the array is matched by a
	 * predicate, not joined against, so repeating an id cannot repeat its row. The
	 * digest is computed over what comes back, so a duplicated row would rename
	 * every analysis.
	 */
	@Test
	void anIdNamedTwiceStillYieldsOneRow() {
		List<Long> photos = givenFingerprintedPhotos();

		Long[] withRepeats = new Long[] { photos.getFirst(), photos.getFirst(), photos.getFirst() };

		Assertions.assertThat(mediaFingerprintRepository.findPhotoCompositionRows(FingerprintKind.PHOTO_PHASH,
				PHOTO_ALGORITHM, withRepeats)).hasSize(1);
	}

	/**
	 * Nothing eligible is not an error, and it is reachable: a library whose files
	 * are all excluded, or one whose fingerprints have not been computed yet.
	 * Every one of the four answers no rows rather than failing or, worse,
	 * answering about everything.
	 */
	@Test
	void anEmptySetSelectsNothingRatherThanEverything() {
		givenFingerprintedPhotos();
		givenFingerprintedVideos();

		Long[] none = new Long[0];

		Assertions.assertThat(mediaFingerprintRepository.findPhotoCompositionRows(FingerprintKind.PHOTO_PHASH,
				PHOTO_ALGORITHM, none)).isEmpty();
		Assertions.assertThat(mediaFingerprintRepository.findFingerprintedPhotos(FingerprintKind.PHOTO_PHASH,
				PHOTO_ALGORITHM, none)).isEmpty();
		Assertions.assertThat(mediaFingerprintRepository.findVideoCompositionRows(FingerprintKind.VIDEO_PHASH,
				VIDEO_ALGORITHM, none)).isEmpty();
		Assertions.assertThat(mediaFingerprintRepository.findFingerprintedVideoFrames(FingerprintKind.VIDEO_PHASH,
				VIDEO_ALGORITHM, none)).isEmpty();
	}

	/**
	 * The same ceiling, one step later and with a different type. A run does not
	 * stop at choosing and reading its files: it then asks what each of them is
	 * worth - resolution, capture date, whether there is EXIF - to decide which of
	 * a group to recommend keeping. That question is asked about every candidate
	 * analysed, so it carries the whole library too, as public ids rather than
	 * catalog ids. Proven separately because binding an array of {@code uuid} is
	 * not the same as binding one of {@code bigint}, and a type the driver refused
	 * to send as an array would have been found here rather than in production.
	 */
	@Test
	void namesMorePublicIdsThanTheWireProtocolCouldEverHaveCarried() {
		List<Long> photos = givenFingerprintedPhotos();
		List<Long> videos = givenFingerprintedVideos();

		UUID[] ids = new UUID[BEYOND_THE_PROTOCOL_CEILING];

		for (int index = 0; index < ids.length; index++) {
			ids[index] = UUID.randomUUID();
		}

		for (int index = 0; index < photos.size(); index++) {
			ids[index] = publicIdOf(photos.get(index));
		}

		for (int index = 0; index < videos.size(); index++) {
			ids[photos.size() + index] = publicIdOf(videos.get(index));
		}

		Assertions.assertThat(ids).hasSizeGreaterThan(65_535);
		Assertions.assertThat(mediaQualityRepository.findByPublicIdIn(ids))
				.as("both media types, and none of the ids that name nothing").hasSize(PHOTOS + VIDEOS);
	}

	/**
	 * Reading a published analysis asks for the files of the groups on one page,
	 * and a page bounds the number of groups rather than what is inside them: a
	 * burst, or the same export catalogued repeatedly, clusters into a single
	 * group of any size. So this set grows with the library too, and it is bound
	 * the same way. The result is keyed by id by the caller, so what has to hold
	 * is the content rather than an order this query never promised.
	 */
	@Test
	void namesEveryMemberOfAGroupThatHasNoSizeLimit() {
		List<Long> photos = givenFingerprintedPhotos();
		List<Long> videos = givenFingerprintedVideos();

		UUID[] ids = new UUID[BEYOND_THE_PROTOCOL_CEILING];

		for (int index = 0; index < ids.length; index++) {
			ids[index] = UUID.randomUUID();
		}

		for (int index = 0; index < photos.size(); index++) {
			ids[index] = publicIdOf(photos.get(index));
		}

		for (int index = 0; index < videos.size(); index++) {
			ids[photos.size() + index] = publicIdOf(videos.get(index));
		}

		Assertions.assertThat(ids).hasSizeGreaterThan(65_535);
		Assertions.assertThat(mediaFingerprintRepository.findSimilarityMembers(ids))
				.as("both media types, and nothing for the ids that name no file")
				.hasSize(PHOTOS + VIDEOS);
	}

	@Test
	void aSmallGroupIsReadTheSameWay() {
		List<Long> photos = givenFingerprintedPhotos();

		UUID[] ids = new UUID[] { publicIdOf(photos.getFirst()) };

		Assertions.assertThat(mediaFingerprintRepository.findSimilarityMembers(ids)).hasSize(1);
	}

	@Test
	void anEmptyGroupReadsNothing() {
		givenFingerprintedPhotos();

		Assertions.assertThat(mediaFingerprintRepository.findSimilarityMembers(new UUID[0])).isEmpty();
	}

	@Test
	void anEmptySetOfPublicIdsSelectsNothing() {
		givenFingerprintedPhotos();

		Assertions.assertThat(mediaQualityRepository.findByPublicIdIn(new UUID[0])).isEmpty();
	}

	/**
	 * The real ids first, then ids of files that do not exist, up to a total well
	 * past what the protocol would have carried. The padding starts far above any
	 * generated key so it cannot collide with a real row.
	 */
	private Long[] paddedTo(int total, List<Long> real) {
		Long[] ids = new Long[total];

		for (int index = 0; index < real.size(); index++) {
			ids[index] = real.get(index);
		}

		for (int index = real.size(); index < total; index++) {
			ids[index] = Long.MAX_VALUE - index;
		}

		return ids;
	}

	private List<Long> givenFingerprintedPhotos() {
		return List.of(fingerprintedPhoto(0), fingerprintedPhoto(1), fingerprintedPhoto(2));
	}

	private List<Long> givenFingerprintedVideos() {
		return List.of(fingerprintedVideo(0), fingerprintedVideo(1));
	}

	private Long fingerprintedPhoto(int index) {
		Long id = catalogued("photo-" + index, ".jpg", FileType.PHOTO, false);


		mediaFingerprintRepository.saveAndFlush(fingerprint(id, FingerprintKind.PHOTO_PHASH, PHOTO_ALGORITHM, 0));

		return id;
	}

	private Long fingerprintedVideo(int index) {
		Long id = catalogued("video-" + index, ".mp4", FileType.VIDEO, true);

		for (int frame = 0; frame < FRAMES_PER_VIDEO; frame++) {
			mediaFingerprintRepository
					.saveAndFlush(fingerprint(id, FingerprintKind.VIDEO_PHASH, VIDEO_ALGORITHM, frame));
		}

		return id;
	}

	private MediaFingerprint fingerprint(Long catalogFileId, FingerprintKind kind, String algorithm, int sampleIndex) {
		return MediaFingerprint.builder().catalogFileId(catalogFileId).kind(kind).algorithm(algorithm)
				.sampleIndex(sampleIndex).hashBytes(new byte[32]).sampleBytes(new byte[1024])
				.computedAt(LocalDateTime.now()).build();
	}

	private UUID publicIdOf(Long catalogFileId) {
		return catalogFileRepository.findById(catalogFileId).orElseThrow().getPublicId();
	}

	private Long catalogued(String name, String extension, FileType fileType, boolean withVideo) {
		String folder = "D:" + (char) 92 + "biblioteca";
		String path = folder + (char) 92 + name + extension;

		CatalogFile file = CatalogFile.builder().fileKey(path).fileName(name + extension)
				.extension(extension.substring(1)).sizeBytes(1L).modifiedAt(LocalDateTime.now()).fileType(fileType)
				.lifecycleStatus(LifecycleStatus.ACTIVE).build();
		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder(folder)
				.originalPath(path).originalFolder(folder).build());

		if (withVideo) {
			file.setVideo(Video.builder().catalogFile(file).durationSeconds(10.0).build());
		}

		return catalogFileRepository.saveAndFlush(file).getId();
	}
}