package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintRebuildTask;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintRebuildTaskId;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingPhoto;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingVideo;

/**
 * What each rebuild still owes.
 *
 * <p>
 * The count below is the single answer to "is this rebuild finished", and it is
 * exact because a task only ever leaves in the same transaction that wrote the
 * outcome for its file - the fingerprint that replaced it, or the failure that
 * spent its last attempt. Zero rows means every candidate was considered, which
 * is a different and weaker claim than every candidate having succeeded.
 */
public interface FingerprintRebuildTaskRepository
		extends JpaRepository<FingerprintRebuildTask, FingerprintRebuildTaskId> {

	/** How much of this rebuild is left; zero is the whole of "finished". */
	long countByKindAndAlgorithm(FingerprintKind kind, String algorithm);

	boolean existsByKindAndAlgorithm(FingerprintKind kind, String algorithm);

	/**
	 * Every photo the catalog can fingerprint, owed at once.
	 *
	 * <p>
	 * Set-based because the candidate set is a query the database already answers,
	 * and a rebuild of a hundred thousand photos has no business being a hundred
	 * thousand round trips. {@code ON CONFLICT DO NOTHING} is what makes asking
	 * twice safe: a second request tops the list back up to the whole library
	 * without disturbing what is already owed, and two lists for one target cannot
	 * exist because the key forbids it.
	 *
	 * <p>
	 * The placement is required for the same reason the pending query joins it - a
	 * file with no path on disk is not something ffmpeg can be pointed at, so
	 * owing it would be owing work nobody can ever do.
	 *
	 * @return how many tasks this call added
	 */
	@Modifying
	@Query(value = """
			INSERT INTO fingerprint_rebuild_task (kind, algorithm, catalog_file_id, seeded_at)
			SELECT :kind, :algorithm, m.id, :seededAt
			  FROM catalog_file m
			  JOIN catalog_file_location l ON l.catalog_file_id = m.id
			 WHERE m.file_type = 'PHOTO' AND m.lifecycle_status = 'ACTIVE'
			ON CONFLICT (kind, algorithm, catalog_file_id) DO NOTHING
			""", nativeQuery = true)
	int seedPhotos(@Param("kind") String kind, @Param("algorithm") String algorithm,
			@Param("seededAt") LocalDateTime seededAt);

	/**
	 * The same for videos, which additionally need the duration the frame samples
	 * are placed by: a video the catalog has not measured yet cannot be sampled,
	 * and the pending query has always said so.
	 *
	 * @return how many tasks this call added
	 */
	@Modifying
	@Query(value = """
			INSERT INTO fingerprint_rebuild_task (kind, algorithm, catalog_file_id, seeded_at)
			SELECT :kind, :algorithm, m.id, :seededAt
			  FROM catalog_file m
			  JOIN catalog_file_location l ON l.catalog_file_id = m.id
			  JOIN video v ON v.catalog_file_id = m.id
			 WHERE m.file_type = 'VIDEO' AND m.lifecycle_status = 'ACTIVE'
			ON CONFLICT (kind, algorithm, catalog_file_id) DO NOTHING
			""", nativeQuery = true)
	int seedVideos(@Param("kind") String kind, @Param("algorithm") String algorithm,
			@Param("seededAt") LocalDateTime seededAt);

	/**
	 * The photos a rebuild still owes, in the order the catalog keeps them.
	 *
	 * <p>
	 * <b>Nothing here asks whether the file already has a fingerprint</b>, and that
	 * is the whole difference from the incremental queue: during a rebuild every
	 * candidate has one, because the one it is replacing is still published. Asking
	 * would return an empty batch and call the rebuild finished before it started.
	 *
	 * <p>
	 * The attempt budget is still consulted. Once the work list is the only ledger
	 * a spent budget will be what consumes the task, and this filter becomes
	 * redundant - but it is what makes the drain terminate meanwhile, and a reader
	 * that hands the same undecodable file back on every batch would never stop.
	 *
	 * <p>
	 * Placement and lifecycle are joined for the same reason the incremental query
	 * joins them: what is handed to the decoder is a path, and a file the catalog
	 * has since lost sight of is not one to point ffmpeg at.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingPhoto(m.id, l.currentPath, m.contentRevision)
			FROM FingerprintRebuildTask t
			JOIN CatalogFile m ON m.id = t.catalogFileId
			JOIN m.location l
			WHERE t.kind = :kind AND t.algorithm = :algorithm
			  AND m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.PHOTO
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  AND NOT EXISTS (SELECT 1 FROM FingerprintFailure fe
			                  WHERE fe.catalogFileId = m.id AND fe.kind = :kind AND fe.algorithm = :algorithm
			                    AND fe.attempts >= :maxAttempts)
			ORDER BY m.id ASC
			""")
	List<PendingPhoto> findOwedPhotos(@Param("kind") FingerprintKind kind, @Param("algorithm") String algorithm,
			@Param("maxAttempts") int maxAttempts, Pageable pageable);

	/**
	 * The videos a rebuild still owes, with the duration its frame samples are
	 * placed by - the one requirement the video queue has and the photo one does
	 * not.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingVideo(m.id, l.currentPath, v.durationSeconds, m.contentRevision)
			FROM FingerprintRebuildTask t
			JOIN CatalogFile m ON m.id = t.catalogFileId
			JOIN m.location l
			JOIN m.video v
			WHERE t.kind = :kind AND t.algorithm = :algorithm
			  AND m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.VIDEO
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  AND NOT EXISTS (SELECT 1 FROM FingerprintFailure fe
			                  WHERE fe.catalogFileId = m.id AND fe.kind = :kind AND fe.algorithm = :algorithm
			                    AND fe.attempts >= :maxAttempts)
			ORDER BY m.id ASC
			""")
	List<PendingVideo> findOwedVideos(@Param("kind") FingerprintKind kind, @Param("algorithm") String algorithm,
			@Param("maxAttempts") int maxAttempts, Pageable pageable);

	/**
	 * Forgets the debts of files that stopped being candidates.
	 *
	 * <p>
	 * The predicate is the seed's, negated: a task is owed while its file is one
	 * the seed would write down today. The one condition that really changes
	 * underneath a long rebuild is the lifecycle - a reconcile marks a file missing
	 * when it is no longer where the catalog says - and a debt nobody can ever pay
	 * would keep the rebuild open for as long as the file stayed away. Physical
	 * removal is already covered by the foreign key.
	 *
	 * <p>
	 * Dropping the debt is not deciding the file needs no fingerprint. If it comes
	 * back, the ordinary queue asks the ordinary question about it again.
	 *
	 * @return how many debts were dropped
	 */
	@Modifying
	@Query(value = """
			DELETE FROM fingerprint_rebuild_task t
			 WHERE t.kind = :kind AND t.algorithm = :algorithm
			   AND NOT EXISTS (SELECT 1 FROM catalog_file m
			                     JOIN catalog_file_location l ON l.catalog_file_id = m.id
			                    WHERE m.id = t.catalog_file_id
			                      AND m.file_type = :fileType AND m.lifecycle_status = 'ACTIVE')
			""", nativeQuery = true)
	int discardIneligiblePhotos(@Param("kind") String kind, @Param("algorithm") String algorithm,
			@Param("fileType") String fileType);

	/** The same for videos, which also need the duration they are sampled by. */
	@Modifying
	@Query(value = """
			DELETE FROM fingerprint_rebuild_task t
			 WHERE t.kind = :kind AND t.algorithm = :algorithm
			   AND NOT EXISTS (SELECT 1 FROM catalog_file m
			                     JOIN catalog_file_location l ON l.catalog_file_id = m.id
			                     JOIN video v ON v.catalog_file_id = m.id
			                    WHERE m.id = t.catalog_file_id
			                      AND m.file_type = 'VIDEO' AND m.lifecycle_status = 'ACTIVE')
			""", nativeQuery = true)
	int discardIneligibleVideos(@Param("kind") String kind, @Param("algorithm") String algorithm);

	/**
	 * Consumes the task of one file, which is only ever called from inside the
	 * transaction that persisted what became of it.
	 *
	 * @return how many rows went, so a caller can tell a task it consumed from one
	 * that had already gone
	 */
	@Modifying
	@Transactional
	@Query("""
			delete from FingerprintRebuildTask t
			 where t.kind = :kind and t.algorithm = :algorithm and t.catalogFileId = :catalogFileId
			""")
	int consume(@Param("kind") FingerprintKind kind, @Param("algorithm") String algorithm,
			@Param("catalogFileId") Long catalogFileId);
}