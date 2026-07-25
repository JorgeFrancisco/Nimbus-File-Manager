package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingPhoto;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingVideo;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoFrameRawResponse;

/**
 * Results side of the visual fingerprints. Reads never mix algorithms: every
 * query is scoped to a single {@code (kind, algorithm)} so incompatible hashes
 * are never compared to each other.
 */
public interface MediaFingerprintRepository extends JpaRepository<MediaFingerprint, Long> {

	/**
	 * Count of distinct catalog files that already have a fingerprint - not the raw
	 * row count. A video stores one row per sampled frame, so counting rows would
	 * report frames, not videos (inflating "done" past the video total); counting
	 * distinct {@code catalogFileId} keeps "done" in the same unit as
	 * {@code countPendingVideos}/{@code countPendingPhotos} for both kinds.
	 */
	@Query("""
			SELECT count(DISTINCT f.catalogFileId)
			FROM MediaFingerprint f
			WHERE f.kind = :kind AND f.algorithm = :algorithm
			""")
	long countFingerprintedCatalogFiles(@Param("kind") FingerprintKind kind, @Param("algorithm") String algorithm);

	long deleteByKindAndAlgorithm(FingerprintKind kind, String algorithm);

	/**
	 * Cheap signature ({@code [count, maxCatalogFileId, maxComputedAt]}) of the exact
	 * set the similarity grouping consumes - i.e. {@code ACTIVE} cataloged photos
	 * that have a fingerprint - used to invalidate the similar-photos cache. It
	 * mirrors the {@code lifecycleStatus = ACTIVE} filter of
	 * {@link #findFingerprintedPhotos}, so quarantining/restoring a photo (which
	 * flips the lifecycle, not the fingerprint) changes the count and forces a
	 * recompute. No hash/luminance is loaded.
	 */
	@Query("""
			SELECT COUNT(f), COALESCE(MAX(m.id), 0), MAX(f.computedAt)
			FROM MediaFingerprint f
			JOIN CatalogFile m ON m.id = f.catalogFileId
			WHERE f.kind = :kind AND f.algorithm = :algorithm
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			""")
	List<Object[]> fingerprintSignature(@Param("kind") FingerprintKind kind, @Param("algorithm") String algorithm);

	boolean existsByCatalogFileIdAndKindAndAlgorithmAndSampleIndex(Long catalogFileId, FingerprintKind kind,
			String algorithm, Integer sampleIndex);

	/**
	 * Cataloged, non-deleted, still-existing photos that already have a fingerprint
	 * of the given kind/algorithm - the candidates for the similarity grouping.
	 * Replaces the old {@code photo.phash} projection; {@code pageable} is a safety
	 * cap on how many candidates are loaded, not real pagination.
	 */
	@Query(value = """
			SELECT new br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRawResponse(
				m.publicId, f.hashBytes, f.sampleBytes, m.fileName, m.extension, m.sizeBytes,
				l.currentPath, l.currentFolder, m.modifiedAt)
			FROM MediaFingerprint f
			JOIN CatalogFile m ON m.id = f.catalogFileId
			LEFT JOIN m.location l
			WHERE f.kind = :kind AND f.algorithm = :algorithm
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			ORDER BY m.id ASC
			""", countQuery = """
			SELECT count(f)
			FROM MediaFingerprint f
			JOIN CatalogFile m ON m.id = f.catalogFileId
			WHERE f.kind = :kind AND f.algorithm = :algorithm
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			""")
	Page<PhotoHashRawResponse> findFingerprintedPhotos(@Param("kind") FingerprintKind kind,
			@Param("algorithm") String algorithm, Pageable pageable);

	/**
	 * The derived pending queue: photos with no fingerprint for this kind/algorithm
	 * and no exhausted failure ({@code attempts >= :maxAttempts}). Items with fewer
	 * attempts are still returned, so the job retries them up to the bound.
	 * {@code pageable} is a batch limit (always page 0), not real pagination -
	 * processed items leave the set.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingPhoto(m.id, l.currentPath)
			FROM CatalogFile m
			JOIN m.location l
			WHERE m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.PHOTO
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  AND NOT EXISTS (SELECT 1 FROM MediaFingerprint f
			                  WHERE f.catalogFileId = m.id AND f.kind = :kind AND f.algorithm = :algorithm)
			  AND NOT EXISTS (SELECT 1 FROM FingerprintFailure fe
			                  WHERE fe.catalogFileId = m.id AND fe.kind = :kind AND fe.algorithm = :algorithm
			                    AND fe.attempts >= :maxAttempts)
			ORDER BY m.id ASC
			""")
	List<PendingPhoto> findPendingPhotos(@Param("kind") FingerprintKind kind, @Param("algorithm") String algorithm,
			@Param("maxAttempts") int maxAttempts, Pageable pageable);

	@Query("""
			SELECT count(m)
			FROM CatalogFile m
			WHERE m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.PHOTO
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  AND NOT EXISTS (SELECT 1 FROM MediaFingerprint f
			                  WHERE f.catalogFileId = m.id AND f.kind = :kind AND f.algorithm = :algorithm)
			  AND NOT EXISTS (SELECT 1 FROM FingerprintFailure fe
			                  WHERE fe.catalogFileId = m.id AND fe.kind = :kind AND fe.algorithm = :algorithm
			                    AND fe.attempts >= :maxAttempts)
			""")
	long countPendingPhotos(@Param("kind") FingerprintKind kind, @Param("algorithm") String algorithm,
			@Param("maxAttempts") int maxAttempts);

	/**
	 * All sampled frames of every {@code ACTIVE} fingerprinted video (of the given
	 * kind/algorithm), ordered so a video's frames are contiguous and in
	 * {@code sampleIndex} order - the grouping reassembles them per video. The
	 * duration and display dimensions feed the cheap candidate bucketing.
	 * {@code pageable} is a safety cap on how many frame rows are loaded, not real
	 * pagination.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoFrameRawResponse(
				m.publicId, f.sampleIndex, f.positionMs, f.hashBytes, f.sampleBytes,
				m.fileName, m.extension, m.sizeBytes, l.currentPath, l.currentFolder, m.modifiedAt,
				v.durationSeconds, md.displayWidth, md.displayHeight)
			FROM MediaFingerprint f
			JOIN CatalogFile m ON m.id = f.catalogFileId
			JOIN m.video v
			LEFT JOIN m.metadata md
			LEFT JOIN m.location l
			WHERE f.kind = :kind AND f.algorithm = :algorithm
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			ORDER BY m.id ASC, f.sampleIndex ASC
			""")
	List<VideoFrameRawResponse> findFingerprintedVideoFrames(@Param("kind") FingerprintKind kind,
			@Param("algorithm") String algorithm, Pageable pageable);

	/**
	 * The derived pending queue for videos: videos with no fingerprint for this
	 * kind/algorithm and no exhausted failure. Mirrors {@link #findPendingPhotos}
	 * but joins {@code video} for the duration used to place frame samples.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingVideo(
				m.id, l.currentPath, v.durationSeconds)
			FROM CatalogFile m
			JOIN m.location l
			JOIN m.video v
			WHERE m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.VIDEO
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  AND NOT EXISTS (SELECT 1 FROM MediaFingerprint f
			                  WHERE f.catalogFileId = m.id AND f.kind = :kind AND f.algorithm = :algorithm)
			  AND NOT EXISTS (SELECT 1 FROM FingerprintFailure fe
			                  WHERE fe.catalogFileId = m.id AND fe.kind = :kind AND fe.algorithm = :algorithm
			                    AND fe.attempts >= :maxAttempts)
			ORDER BY m.id ASC
			""")
	List<PendingVideo> findPendingVideos(@Param("kind") FingerprintKind kind, @Param("algorithm") String algorithm,
			@Param("maxAttempts") int maxAttempts, Pageable pageable);

	@Query("""
			SELECT count(m)
			FROM CatalogFile m
			WHERE m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.VIDEO
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  AND NOT EXISTS (SELECT 1 FROM MediaFingerprint f
			                  WHERE f.catalogFileId = m.id AND f.kind = :kind AND f.algorithm = :algorithm)
			  AND NOT EXISTS (SELECT 1 FROM FingerprintFailure fe
			                  WHERE fe.catalogFileId = m.id AND fe.kind = :kind AND fe.algorithm = :algorithm
			                    AND fe.attempts >= :maxAttempts)
			""")
	long countPendingVideos(@Param("kind") FingerprintKind kind, @Param("algorithm") String algorithm,
			@Param("maxAttempts") int maxAttempts);
}