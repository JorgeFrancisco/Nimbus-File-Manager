package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.CompositionRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingPhoto;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingVideo;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoSampleRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.SimilarityMemberFile;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoFrameRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoFrameRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoGateRow;

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

	/**
	 * How many files satisfy every functional rule of a similarity analysis:
	 * fingerprinted with this algorithm, still active, and not hidden by the user's
	 * file or folder exclusions.
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
	 * Every file an analysis will work on: the same eligibility rule as
	 * {@link #countEligibleForSimilarity}, in catalog order and complete.
	 *
	 * <p>
	 * <b>The exclusions are applied here, by the database, and that is the whole
	 * reason this exists rather than a filter over a wider read.</b> They used to
	 * be subtracted in memory from rows the query had already chosen, and a library
	 * whose oldest files sat in excluded folders lost them from the selection
	 * without anything saying so - 4.936 files analysed where 8.000 were announced,
	 * in the case that exposed it. Selecting the eligible set in one place is what
	 * keeps the number the product promises equal to the number it delivers, and
	 * equal to what the count reports on.
	 *
	 * <p>
	 * Ids and not rows, so the callers that need the heavy columns and the one that
	 * needs only the composition select the same files by construction rather than
	 * by two filters that have to be kept identical by hand. {@code DISTINCT}
	 * because a video carries one row per sampled frame and this counts files.
	 *
	 * <p>
	 * The exclusion predicate is deliberately not repeated anywhere else. It is
	 * subtle - see the {@code chr(92)} note on
	 * {@link #countEligibleForSimilarity} - and a second copy is a second chance to
	 * get the escaping wrong.
	 *
	 * <p>
	 * <b>There is no limit any more.</b> Neither medium caps: an analysis is about
	 * the whole eligible library, and a partial answer presented as a complete one
	 * was the defect removing the cap closed. What used to be a {@code LIMIT} is
	 * gone rather than parameterised - a truncation nobody asks for is a truncation
	 * nobody can trip over, and a parameter kept alive only so a test can reproduce
	 * retired behaviour is a parameter that will be passed again one day.
	 */
	@Query(value = """
			SELECT DISTINCT m.id
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
			ORDER BY m.id ASC
			""", nativeQuery = true)
	List<Long> findEligibleForSimilarity(@Param("kind") String kind, @Param("algorithm") String algorithm);

	/**
	 * The photo candidates of an analysis, as ids and folders only.
	 *
	 * <p>
	 * Deliberately a copy of {@code findFingerprintedPhotos}' filter and ordering,
	 * with a lighter projection: the application asks this before queueing, to
	 * identify which analysis it is asking for, and identifying it requires
	 * selecting exactly the files the worker will select. Any divergence between
	 * the two - a condition here, a different sort - makes the digest name a subset
	 * nobody analysed, so the pair is verified by
	 * {@code SimilarityCompositionContractIntegrationTest} rather than by reading.
	 *
	 * <p>
	 * The cap is no longer here: both sides take the ids from
	 * {@link #findEligibleForSimilarity}, which is what makes them select the same
	 * files by construction instead of by two filters kept identical by hand.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.CompositionRow(
				m.publicId, l.currentFolder)
			FROM MediaFingerprint f
			JOIN CatalogFile m ON m.id = f.catalogFileId
			LEFT JOIN m.location l
			WHERE f.kind = :kind AND f.algorithm = :algorithm
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  AND m.id IN :catalogFileIds
			ORDER BY m.id ASC
			""")
	List<CompositionRow> findPhotoCompositionRows(@Param("kind") FingerprintKind kind,
			@Param("algorithm") String algorithm, @Param("catalogFileIds") Collection<Long> catalogFileIds);

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
			  AND m.id IN :catalogFileIds
			ORDER BY m.id ASC, f.sampleIndex ASC
			""")
	List<CompositionRow> findVideoCompositionRows(@Param("kind") FingerprintKind kind,
			@Param("algorithm") String algorithm, @Param("catalogFileIds") Collection<Long> catalogFileIds);

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

	/**
	 * Everything one file holds for a target, so a replacement can put back a
	 * different number of rows than it took away.
	 *
	 * <p>
	 * A video is sampled by its duration, and a duration the catalog re-read is a
	 * different set of frames: replacing sample by sample would leave the tail of
	 * the old set behind, attributed to a hash that never produced it. Called only
	 * from inside the transaction that writes the new set.
	 *
	 * <p>
	 * A statement rather than a derived delete, and that is the whole of it
	 * working. A derived delete queues the removals in the persistence context,
	 * and Hibernate orders inserts before deletes at flush - so the replacement
	 * reached the unique key while the rows it was replacing were still there.
	 * This one is executed when it is called, which is what "the old set goes
	 * before the new set arrives" has to mean.
	 *
	 * @return how many rows the file had
	 */
	@Modifying
	@Query("""
			delete from MediaFingerprint f
			 where f.catalogFileId = :catalogFileId and f.kind = :kind and f.algorithm = :algorithm
			""")
	int deleteByCatalogFileIdAndKindAndAlgorithm(@Param("catalogFileId") Long catalogFileId,
			@Param("kind") FingerprintKind kind, @Param("algorithm") String algorithm);

	boolean existsByCatalogFileIdAndKindAndAlgorithmAndSampleIndex(Long catalogFileId, FingerprintKind kind,
			String algorithm, Integer sampleIndex);

	/**
	 * Cataloged, non-deleted, still-existing photos that already have a fingerprint
	 * of the given kind/algorithm - the candidates for the similarity grouping,
	 * restricted to the files {@link #findEligibleForSimilarity} chose. The
	 * exclusions were applied there, so every row this returns is a file the
	 * analysis is meant to see.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRawResponse(
				m.id, m.publicId, f.hashBytes, f.sampleBytes, m.fileName, m.extension, m.sizeBytes,
				l.currentPath, l.currentFolder, m.modifiedAt)
			FROM MediaFingerprint f
			JOIN CatalogFile m ON m.id = f.catalogFileId
			LEFT JOIN m.location l
			WHERE f.kind = :kind AND f.algorithm = :algorithm
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  AND m.id IN :catalogFileIds
			ORDER BY m.id ASC
			""")
	List<PhotoHashRawResponse> findFingerprintedPhotos(@Param("kind") FingerprintKind kind,
			@Param("algorithm") String algorithm, @Param("catalogFileIds") Collection<Long> catalogFileIds);

	/**
	 * Every fingerprinted photo's hash, without its sample and without asking
	 * whether the file is eligible today.
	 *
	 * <p>
	 * Both omissions are deliberate. The sample is thirty-two times the size of
	 * the hash and an incremental run needs it only for the handful of pairs the
	 * distance filter lets through, so it is fetched afterwards by
	 * {@link #findPhotoSamples}. And the lifecycle filter is absent because this
	 * feeds the comparison against the <em>covered</em> set: a file in quarantine
	 * or hidden by an exclusion is still part of the relation universe, and
	 * skipping it would leave the pair between it and the newcomer evaluated by
	 * nobody - the counterexample {@code SimilarityCoverageModelTest} holds.
	 *
	 * <p>
	 * No {@code IN} list either: the covered set is the size of the library, and
	 * naming it would be more bind parameters than the protocol allows. The caller
	 * keeps the rows it wants.
	 */
	@Query(value = """
			SELECT f.catalog_file_id AS catalogFileId, f.hash_bytes AS hashBytes
			FROM media_fingerprint f
			WHERE f.kind = :kind AND f.algorithm = :algorithm AND f.hash_bytes IS NOT NULL
			ORDER BY f.catalog_file_id
			""", nativeQuery = true)
	List<PhotoHashRow> findPhotoHashes(@Param("kind") String kind, @Param("algorithm") String algorithm);

	/**
	 * The luminance samples of the photos a distance scan actually wants to
	 * compare - a small set by construction, so naming them is affordable here
	 * where naming the whole library would not be.
	 */
	@Query(value = """
			SELECT f.catalog_file_id AS catalogFileId, f.sample_bytes AS sampleBytes
			FROM media_fingerprint f
			WHERE f.kind = :kind AND f.algorithm = :algorithm
			  AND f.sample_bytes IS NOT NULL
			  AND f.catalog_file_id = ANY(:catalogFileIds)
			""", nativeQuery = true)
	List<PhotoSampleRow> findPhotoSamples(@Param("kind") String kind, @Param("algorithm") String algorithm,
			@Param("catalogFileIds") Long[] catalogFileIds);

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
				m.id, m.publicId, f.sampleIndex, f.positionMs, f.hashBytes, f.sampleBytes,
				m.fileName, m.extension, m.sizeBytes, l.currentPath, l.currentFolder, m.modifiedAt,
				v.durationSeconds, md.displayWidth, md.displayHeight)
			FROM MediaFingerprint f
			JOIN CatalogFile m ON m.id = f.catalogFileId
			JOIN m.video v
			LEFT JOIN m.metadata md
			LEFT JOIN m.location l
			WHERE f.kind = :kind AND f.algorithm = :algorithm
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  AND m.id IN :catalogFileIds
			ORDER BY m.id ASC, f.sampleIndex ASC
			""")
	List<VideoFrameRawResponse> findFingerprintedVideoFrames(@Param("kind") FingerprintKind kind,
			@Param("algorithm") String algorithm, @Param("catalogFileIds") Collection<Long> catalogFileIds);

	/**
	 * Every fingerprinted video's duration and display size, which is all the
	 * cheap gates need - see {@link VideoGateRow} for why the frames are not read
	 * with them, and why this has neither a lifecycle filter nor an id list.
	 *
	 * <p>
	 * {@code DISTINCT} because a video has one fingerprint row per sampled frame
	 * and this question is about the video.
	 */
	@Query(value = """
			SELECT DISTINCT f.catalog_file_id AS catalogFileId, v.duration_seconds AS durationSeconds,
			       md.display_width AS displayWidth, md.display_height AS displayHeight
			FROM media_fingerprint f
			JOIN video v ON v.catalog_file_id = f.catalog_file_id
			LEFT JOIN media_metadata md ON md.catalog_file_id = f.catalog_file_id
			WHERE f.kind = :kind AND f.algorithm = :algorithm
			ORDER BY f.catalog_file_id
			""", nativeQuery = true)
	List<VideoGateRow> findVideoGateRows(@Param("kind") String kind, @Param("algorithm") String algorithm);

	/**
	 * The frames of the videos a surviving pair named, ordered so a video's frames
	 * are contiguous and in {@code sampleIndex} order.
	 *
	 * <p>
	 * No lifecycle filter, for the same reason {@link #findVideoGateRows} has
	 * none: an arrival compares against the covered set, and a covered file hidden
	 * today is still part of the relation universe.
	 */
	@Query(value = """
			SELECT f.catalog_file_id AS catalogFileId, f.sample_index AS sampleIndex,
			       f.hash_bytes AS hashBytes, f.sample_bytes AS sampleBytes
			FROM media_fingerprint f
			WHERE f.kind = :kind AND f.algorithm = :algorithm
			  AND f.hash_bytes IS NOT NULL AND f.sample_bytes IS NOT NULL
			  AND f.catalog_file_id = ANY(:catalogFileIds)
			ORDER BY f.catalog_file_id, f.sample_index
			""", nativeQuery = true)
	List<VideoFrameRow> findVideoFrames(@Param("kind") String kind, @Param("algorithm") String algorithm,
			@Param("catalogFileIds") Long[] catalogFileIds);

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