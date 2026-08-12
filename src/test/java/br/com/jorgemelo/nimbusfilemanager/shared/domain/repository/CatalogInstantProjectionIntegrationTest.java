package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.DuplicateRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateFileRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateFileWithShaRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.SimilarityMemberFile;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoFrameRawResponse;
import br.com.jorgemelo.nimbusfilemanager.media.domain.repository.MediaSearchRepository;
import br.com.jorgemelo.nimbusfilemanager.media.domain.repository.projection.MediaSearchRawResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Video;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.CameraFilter;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.MediaScaleFilter;
import br.com.jorgemelo.nimbusfilemanager.media.domain.repository.projection.MediaSearchFilter;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;

/**
 * The moment a file was written to, carried whole from the column to every
 * projection that reads it.
 *
 * <p>
 * These six queries share one defect and therefore one test. Each of them
 * projects {@code catalog_file.modified_at} - a point on the timeline - into a
 * record, and each of them had declared it as a local date-time. Hibernate
 * resolves a constructor projection by type, so none of them had a constructor
 * to call: every one failed at <em>bootstrap</em>, taking its repository bean,
 * the services depending on it and the whole application context down with it.
 * The screens they serve - duplicates, similarity, search - could not open at
 * all.
 *
 * <p>
 * A unit test cannot see any of this: it is not a compile error, and mocking
 * the repository asserts about a query that never runs. So the fixture is small
 * and real, and every case executes the actual query against PostgreSQL and
 * reads the instant back out of the materialised record.
 */
class CatalogInstantProjectionIntegrationTest extends SharedPostgresIntegrationTest {

	/** Deliberately not midnight and not whole: a truncation would show. */
	private static final Instant WRITTEN_AT = Instant.parse("2026-03-14T09:26:53Z");

	/** What the two photographs are: the same bytes twice. */
	private static final String SHA = "sha-shared-by-two-copies";
	/** The video is other content, so it is not one of those duplicates. */
	private static final String VIDEO_SHA = "sha-of-the-clip";
	private static final String PHOTO_ALGORITHM = "DCT_PHASH_256_V1";
	private static final String VIDEO_ALGORITHM = "VIDEO_PHASH_V1";

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private DuplicateRepository duplicateRepository;

	@Autowired
	private MediaFingerprintRepository mediaFingerprintRepository;

	@Autowired
	private MediaSearchRepository mediaSearchRepository;

	private CatalogFile firstPhoto;
	private CatalogFile secondPhoto;
	private CatalogFile video;

	@BeforeEach
	void aLibraryWithTwoIdenticalPhotosAndOneVideo() {
		firstPhoto = photo("primeira.jpg");
		secondPhoto = photo("segunda.jpg");
		video = video("clipe.mp4");

		fingerprint(firstPhoto, FingerprintKind.PHOTO_PHASH, PHOTO_ALGORITHM);
		fingerprint(secondPhoto, FingerprintKind.PHOTO_PHASH, PHOTO_ALGORITHM);
		fingerprint(video, FingerprintKind.VIDEO_PHASH, VIDEO_ALGORITHM);
	}

	@Test
	void theDuplicatesOfOneDigestCarryTheMomentEachCopyWasWritten() {
		List<DuplicateFileRawResponse> files = duplicateRepository.findDuplicateFiles(SHA);

		Assertions.assertThat(files).hasSize(2).extracting(DuplicateFileRawResponse::modifiedAt)
				.containsOnly(WRITTEN_AT);
	}

	@Test
	void theDuplicatesOfSeveralDigestsCarryItToo() {
		List<DuplicateFileWithShaRawResponse> files = duplicateRepository.findDuplicateFilesForShas(List.of(SHA),
				List.of(FileType.PHOTO));

		Assertions.assertThat(files).hasSize(2).extracting(DuplicateFileWithShaRawResponse::modifiedAt)
				.containsOnly(WRITTEN_AT);
	}

	/**
	 * The one that reads both kinds at once, and the only one of the six that also
	 * carries a capture date beside the moment - which stays local, because what a
	 * person reads off a photograph has no offset to keep.
	 */
	@Test
	void theMembersOfAPublishedGroupCarryItAndKeepTheCaptureDateLocal() {
		List<SimilarityMemberFile> members = mediaFingerprintRepository
				.findSimilarityMembers(new UUID[] { firstPhoto.getCatalogFilePublicId(),
						video.getCatalogFilePublicId() });

		Assertions.assertThat(members).hasSize(2).extracting(SimilarityMemberFile::modifiedAt)
				.containsOnly(WRITTEN_AT);
	}

	@Test
	void everyFingerprintedPhotoCarriesIt() {
		List<PhotoHashRawResponse> photos = mediaFingerprintRepository.findFingerprintedPhotos(
				FingerprintKind.PHOTO_PHASH, PHOTO_ALGORITHM,
				new Long[] { firstPhoto.getId(), secondPhoto.getId() });

		Assertions.assertThat(photos).hasSize(2).extracting(PhotoHashRawResponse::modifiedAt)
				.containsOnly(WRITTEN_AT);
	}

	@Test
	void everyFingerprintedVideoFrameCarriesIt() {
		List<VideoFrameRawResponse> frames = mediaFingerprintRepository.findFingerprintedVideoFrames(
				FingerprintKind.VIDEO_PHASH, VIDEO_ALGORITHM, new Long[] { video.getId() });

		Assertions.assertThat(frames).hasSize(1).extracting(VideoFrameRawResponse::modifiedAt)
				.containsOnly(WRITTEN_AT);
	}

	/**
	 * Search projects two of them - when the file was created and when it was last
	 * written - and orders by the second, so a wrong type here would be a wrong
	 * order as well as a broken query.
	 */
	@Test
	void theSearchResultsCarryBothMomentsTheCatalogHolds() {
		List<MediaSearchRawResponse> found = mediaSearchRepository.search(thisLibrary(), PageRequest.of(0, 10))
				.getContent();

		Assertions.assertThat(found).hasSize(3);
		Assertions.assertThat(found).extracting(MediaSearchRawResponse::modifiedAt).containsOnly(WRITTEN_AT);
		Assertions.assertThat(found).extracting(MediaSearchRawResponse::createdAt).containsOnly(WRITTEN_AT);
	}

	/**
	 * Restricted to the folder this test seeded.
	 *
	 * <p>
	 * The database is shared with every other integration test, and some of them
	 * commit outside the rollback, so "everything in the catalog" is not a
	 * population this test can name. Asking about its own folder makes the answer
	 * the same whatever ran before it.
	 */
	private MediaSearchFilter thisLibrary() {
		return new MediaSearchFilter(null, null, folder(), null, null, null, MediaScaleFilter.ANY, CameraFilter.ANY,
				null);
	}

	private CatalogFile photo(String name) {
		return catalogued(name, FileType.PHOTO, null);
	}

	private CatalogFile video(String name) {
		return catalogued(name, FileType.VIDEO, 12.5);
	}

	private String folder() {
		return "D:" + backslash() + "Media-instant-projection";
	}

	private CatalogFile catalogued(String name, FileType fileType, Double durationSeconds) {
		String folder = folder();

		CatalogFile file = CatalogFile.builder().extension(name.substring(name.indexOf('.') + 1)).sizeBytes(1_024L)
				.sha256(durationSeconds == null ? SHA : VIDEO_SHA).fileType(fileType).createdAt(WRITTEN_AT)
				.modifiedAt(WRITTEN_AT).importedAt(WRITTEN_AT).build();

		file.setLocation(CatalogFileLocation.builder().catalogFile(file)
				.currentPath(folder + backslash() + name).currentFolder(folder).pathFlavor(PathFlavor.WINDOWS)
				.build());

		if (durationSeconds != null) {
			file.setVideo(Video.builder().catalogFile(file).durationSeconds(durationSeconds).build());
		}

		return CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository, file);
	}

	private void fingerprint(CatalogFile file, FingerprintKind kind, String algorithm) {
		mediaFingerprintRepository.saveAndFlush(MediaFingerprint.builder().catalogFileId(file.getId()).kind(kind)
				.algorithm(algorithm).sampleIndex(0).hashBytes(new byte[32]).sampleBytes(new byte[1024])
				.computedAt(LocalDateTime.now()).build());
	}

	/**
	 * Written as a character rather than as an escape so the separator cannot be
	 * miscounted by whoever reads it next.
	 */
	private String backslash() {
		return String.valueOf((char) 92);
	}
}