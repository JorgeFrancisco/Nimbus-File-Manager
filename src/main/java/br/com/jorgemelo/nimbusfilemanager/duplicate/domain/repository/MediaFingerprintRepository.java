package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.CompositionRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingPhoto;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingVideo;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.SimilarityMemberFile;
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
	 * How many files satisfy every functional rule of a similarity analysis, before
	 * any candidate cap: fingerprinted with this algorithm, still active, and not
	 * hidden by the user's file or folder exclusions.
	 *
	 * <p>
	 * The exclusions are applied here, in SQL, rather than counted first and
	 * subtracted later: a file the user told the product to ignore was never
	 * eligible, and a count that included it would report a coverage the analysis
	 * never intended to have. The folder predicate is the same one the exact-tab
	 * queries use - separator-agnostic, with the LIKE wildcards escaped - so both
	 * tabs agree on what "excluded" means.
	 *
	 * <p>
	 * {@code COUNT(DISTINCT)} because a video carries one row per sampled frame;
	 * the question is how many files are eligible, not how many fingerprints.
	 *
	 * <p>
	 * The backslash is written as {@code chr(92)} rather than as a literal, and
	 * that is the fix for a real defect: this is a <em>native</em> query, so what
	 * a Java text block produces reaches PostgreSQL verbatim. Written {@code
	 * '\\\\'} it produced a two-character literal, which
	 * {@code standard_conforming_strings = on} reads as two backslashes - an
	 * {@code ESCAPE} PostgreSQL refuses (22025) and a {@code REPLACE} that would
	 * never match a Windows path anyway. The HQL siblings need the doubling and
	 * this one does not, which is exactly why counting backslashes by eye failed:
	 * {@code chr(92)} has none to count and means one character under any setting.
	 */
	@Query(value = """
			SELECT COUNT(DISTINCT m.id)
			FROM media_fingerprint f
			JOIN catalog_file m ON m.id = f.catalog_file_id
			LEFT JOIN catalog_file_location l ON l.catalog_file_id = m.id
			WHERE f.kind = :kind AND f.algorithm = :algorithm
			  AND m.lifecycle_status = 'ACTIVE'
			  AND NOT EXISTS (SELECT 1 FROM duplicate_file_exclusion fe WHERE fe.public_id = m.public_id)
			  AND NOT EXISTS (SELECT 1 FROM duplicate_folder_exclusion fo
			                  WHERE REPLACE(l.current_folder, chr(92), '/') = fo.folder_path
			                     OR REPLACE(l.current_folder, chr(92), '/')
			                        LIKE REPLACE(REPLACE(fo.folder_path, '%', chr(92) || '%'), '_', chr(92) || '_') || '/%'
			                        ESCAPE chr(92))
			""", nativeQuery = true)
	int countEligibleForSimilarity(@Param("kind") String kind, @Param("algorithm") String algorithm);

	/**
	 * The photo candidates of an analysis, as ids and folders only.
	 *
	 * <p>
	 * Deliberately a copy of {@code findFingerprintedPhotos}' filter, ordering and
	 * limit, with a lighter projection: the application asks this before queueing,
	 * to identify which analysis it is asking for, and identifying it requires
	 * selecting exactly the files the worker will select. Any divergence between
	 * the two - a condition here, a different sort - makes the digest name a subset
	 * nobody analysed, so the pair is verified by
	 * {@code SimilarityCompositionContractIntegrationTest} rather than by reading.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.CompositionRow(
				m.publicId, l.currentFolder)
			FROM MediaFingerprint f
			JOIN CatalogFile m ON m.id = f.catalogFileId
			LEFT JOIN m.location l
			WHERE f.kind = :kind AND f.algorithm = :algorithm
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			ORDER BY m.id ASC
			""")
	List<CompositionRow> findPhotoCompositionRows(@Param("kind") FingerprintKind kind,
			@Param("algorithm") String algorithm, Pageable pageable);

	/**
	 * The video candidates, one row per sampled frame - the same shape the heavy
	 * query returns.
	 *
	 * <p>
	 * One row per frame and not one per video, because the candidate cap counts
	 * frame rows: a video sitting on the cut enters the analysis with the frames
	 * that fit. Collapsing here would select a different set than the worker does,
	 * and precisely at the boundary nobody looks at.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.CompositionRow(
				m.publicId, l.currentFolder)
			FROM MediaFingerprint f
			JOIN CatalogFile m ON m.id = f.catalogFileId
			JOIN m.video v
			LEFT JOIN m.location l
			WHERE f.kind = :kind AND f.algorithm = :algorithm
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			ORDER BY m.id ASC, f.sampleIndex ASC
			""")
	List<CompositionRow> findVideoCompositionRows(@Param("kind") FingerprintKind kind,
			@Param("algorithm") String algorithm, Pageable pageable);

	/**
	 * The catalog side of the members on one page of a published analysis.
	 *
	 * <p>
	 * No filter on lifecycle: a member deleted or quarantined since the analysis is
	 * still part of what was found, and hiding it here would silently rewrite a
	 * published result because the world moved. The status comes back so the screen
	 * can show the member without offering actions over it.
	 *
	 * <p>
	 * A member whose catalog row is gone for good returns nothing at all, and the
	 * reading treats an absent row the same way it treats a non-active one.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.SimilarityMemberFile(
				m.publicId, m.fileName, m.extension, CAST(m.fileType AS string), m.sizeBytes,
				l.currentPath, l.currentFolder, m.modifiedAt, md.displayWidth, md.displayHeight,
				md.captureDate, md.dateSource, m.lifecycleStatus)
			FROM CatalogFile m
			LEFT JOIN m.location l
			LEFT JOIN m.metadata md
			WHERE m.publicId IN :publicIds
			""")
	List<SimilarityMemberFile> findSimilarityMembers(@Param("publicIds") Collection<UUID> publicIds);

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