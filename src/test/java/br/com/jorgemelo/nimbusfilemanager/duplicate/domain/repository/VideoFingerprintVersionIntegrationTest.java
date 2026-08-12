package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * What happens to the videos fingerprinted under the old algorithm when a new
 * one takes over.
 *
 * <p>
 * Seeking to each sampled position reproduces what a sequential decode found
 * almost always - 265 of 267 measured frames, byte for byte - and that "almost"
 * is the whole reason {@code FRAMES_V2} is a separate identifier rather than a
 * faster {@code FRAMES_V1}. Two fingerprints of the same video, one from each,
 * are not interchangeable, so nothing may compare them and nothing may treat a
 * file carrying the old one as already done.
 *
 * <p>
 * None of that needs a migration or a backfill, and these prove it: the queries
 * that decide "pending" and "eligible" both take the algorithm as a parameter,
 * so a library fingerprinted under V1 comes back as work on its own the moment
 * the algorithm string changes. The old rows stay exactly where they are, valid
 * as the historical family they now are, until a rebuild replaces them.
 */
class VideoFingerprintVersionIntegrationTest extends SharedPostgresIntegrationTest {

	private static final String V1 = FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1;

	private static final String V2 = FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V2;

	private static final int MAX_ATTEMPTS = 3;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private MediaFingerprintRepository mediaFingerprintRepository;

	/**
	 * The 188 videos of the real library, in miniature: fingerprinted, and pending
	 * again the moment the algorithm moves. This is the whole migration plan.
	 */
	@Test
	void aVideoFingerprintedUnderTheOldAlgorithmIsPendingUnderTheNewOne() {
		fingerprint(catalogued(), V1);

		Assertions.assertThat(pendingUnder(V2)).as("the old fingerprint is not an answer about the new algorithm")
				.isEqualTo(1);
		Assertions.assertThat(pendingUnder(V1)).as("and it is still an answer about the old one").isZero();
	}

	/** And stops being pending once the new algorithm has answered for it. */
	@Test
	void aVideoFingerprintedUnderTheNewAlgorithmIsNotPendingAgain() {
		fingerprint(catalogued(), V2);

		Assertions.assertThat(pendingUnder(V2)).isZero();
	}

	/**
	 * The two versions of one video coexist in the table - the unique key carries
	 * the algorithm - and neither is counted as the other. That is what keeps a
	 * rebuild from having to delete anything before it can write.
	 */
	@Test
	void bothVersionsOfOneVideoCoexistWithoutBeingConfused() {
		CatalogFile video = catalogued();

		fingerprint(video, V1);
		fingerprint(video, V2);

		Assertions.assertThat(eligibleUnder(V1)).as("one file under the old algorithm").isEqualTo(1);
		Assertions.assertThat(eligibleUnder(V2)).as("and the same file under the new one").isEqualTo(1);
		Assertions.assertThat(pendingUnder(V2)).as("nothing left to do for the new algorithm").isZero();
	}

	/**
	 * Similarity counts what it can compare, and it can only compare within one
	 * algorithm. A library still carrying V1 rows contributes nothing to a V2
	 * analysis - which is what stops an answer from being built out of two
	 * incompatible halves.
	 */
	@Test
	void anAnalysisOfOneVersionNeverCountsTheOther() {
		fingerprint(catalogued(), V1);

		Assertions.assertThat(eligibleUnder(V2)).as("V1 rows are invisible to a V2 analysis").isZero();
		Assertions.assertThat(eligibleUnder(V1)).isEqualTo(1);
	}

	/** Photos are a different kind entirely and none of this reaches them. */
	@Test
	void thePhotoAlgorithmIsUntouchedByTheVideoVersionChange() {
		CatalogFile photo = catalogued(FileType.PHOTO);

		mediaFingerprintRepository.saveAll(List.of(MediaFingerprint.builder().catalogFileId(photo.getId())
				.kind(FingerprintKind.PHOTO_PHASH).algorithm(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1)
				.sampleIndex(0).hashBytes(new byte[32]).sampleBytes(new byte[1024]).computedAt(LocalDateTime.now())
				.build()));
		mediaFingerprintRepository.flush();

		Assertions.assertThat(mediaFingerprintRepository.countPendingPhotos(FingerprintKind.PHOTO_PHASH,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, MAX_ATTEMPTS)).isZero();
	}

	private long pendingUnder(String algorithm) {
		return mediaFingerprintRepository.countPendingVideos(FingerprintKind.VIDEO_PHASH, algorithm, MAX_ATTEMPTS);
	}

	private int eligibleUnder(String algorithm) {
		return mediaFingerprintRepository.countEligibleForSimilarity(FingerprintKind.VIDEO_PHASH.name(), algorithm);
	}

	/** The five rows one video fingerprint is made of, under one algorithm. */
	private void fingerprint(CatalogFile video, String algorithm) {
		for (int sample = 0; sample < 5; sample++) {
			mediaFingerprintRepository.save(MediaFingerprint.builder().catalogFileId(video.getId())
					.kind(FingerprintKind.VIDEO_PHASH).algorithm(algorithm).sampleIndex(sample)
					.positionMs(sample * 1000L).hashBytes(new byte[32]).sampleBytes(new byte[1024])
					.computedAt(LocalDateTime.now()).build());
		}

		mediaFingerprintRepository.flush();
	}

	private CatalogFile catalogued() {
		return catalogued(FileType.VIDEO);
	}

	private CatalogFile catalogued(FileType fileType) {
		String folder = "D:" + backslash() + "library";
		String path = folder + backslash() + System.nanoTime() + ".mp4";

		CatalogFile file = CatalogFile.builder().extension("mp4").sizeBytes(1L).modifiedAt(Instant.now())
				.fileType(fileType).build();

		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder(folder)
				.pathFlavor(PathFlavor.WINDOWS).build());

		return CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository, file);
	}

	private String backslash() {
		return String.valueOf((char) 92);
	}
}