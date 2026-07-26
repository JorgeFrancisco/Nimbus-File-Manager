package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.CatalogExportRow;

public interface CatalogFileRepository extends JpaRepository<CatalogFile, Long> {

	/**
	 * Keyset-paginated flat projection for the catalog export: one row per
	 * {@code catalog_file} with its placement, ordered by id, starting after
	 * {@code lastId}. Keyset (not OFFSET) so exporting hundreds of thousands of
	 * rows stays linear instead of degrading on deep pages, and a DTO projection so
	 * no lazy association is touched while streaming outside a transaction.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.CatalogExportRow(
				mf.id, mf.publicId, mf.fileKey, mf.fileName, mf.extension, mf.sizeBytes, mf.sha256, mf.md5,
				mf.mimeType, CAST(mf.fileType AS string), CAST(mf.lifecycleStatus AS string),
				mf.createdAt, mf.modifiedAt, mf.importedAt, loc.currentPath, loc.originalPath)
			FROM CatalogFile mf
			LEFT JOIN mf.location loc
			WHERE mf.id > :lastId
			ORDER BY mf.id
			""")
	List<CatalogExportRow> findCatalogExportRows(@Param("lastId") Long lastId, Pageable pageable);

	/**
	 * Marks the given files MISSING (absent from disk) and stamps
	 * {@code lifecycle_changed_at} with {@code changedAt}. Only real ACTIVE -&gt;
	 * MISSING transitions are touched: a DELETED file is never downgraded
	 * (invariant preserved) and an already-MISSING file keeps its original
	 * timestamp, so the retention clock the catalog purge uses does not reset on
	 * every reconcile pass.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
			update CatalogFile mf
			   set mf.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.MISSING,
			       mf.lifecycleChangedAt = :changedAt,
			       mf.version = mf.version + 1
			 where mf.id in :ids
			   and mf.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			""")
	int markMissingByIds(@Param("ids") List<Long> ids, @Param("changedAt") LocalDateTime changedAt);

	/**
	 * Permanently removes {@code catalog_file} rows that have been MISSING since
	 * before {@code cutoff}, anchored on {@code lifecycle_changed_at}. Set-based
	 * and reliant on the database foreign keys to handle dependents: location,
	 * metadata, photo and video rows cascade away ({@code ON DELETE CASCADE}),
	 * while movement audit rows are detached, not deleted
	 * ({@code ON DELETE SET NULL}), so history survives. DELETED rows are
	 * intentionally excluded - their removal is owned by the quarantine retention
	 * purge, which also clears the quarantined file and its movement.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
			delete from CatalogFile mf
			 where mf.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.MISSING
			   and mf.lifecycleChangedAt < :cutoff
			""")
	int deleteMissingBefore(@Param("cutoff") LocalDateTime cutoff);

	/**
	 * Bulk-removes every {@code catalog_file} placed at or under a library root,
	 * matched case-insensitively against the location's current or inventory path
	 * (exact root or prefixed with {@code root + separator}). Native and set-based
	 * on purpose: it deletes by subquery over {@code catalog_file_location} without
	 * loading entities, relies on the {@code ON DELETE CASCADE} foreign keys to
	 * wipe the dependent rows, and uses {@code left()/length()} which have no JPQL
	 * equivalent. Used when switching/clearing a library, so it can drop the whole
	 * catalog in one statement.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			DELETE FROM catalog_file mf
			WHERE mf.id IN (
			    SELECT DISTINCT ml.catalog_file_id
			    FROM catalog_file_location ml
			    WHERE lower(ml.current_path) = lower(:root)
			       OR lower(left(ml.current_path, length(:prefix))) = lower(:prefix)
			       OR lower(ml.inventory_path) = lower(:root)
			)
			""", nativeQuery = true)
	int deleteWithinLibrary(@Param("root") String root, @Param("prefix") String prefix);

	Optional<CatalogFile> findByFileKey(String fileKey);

	@EntityGraph(attributePaths = { "location", "media", "photo", "video" })
	@Query("select mf from CatalogFile mf where mf.fileKey = :fileKey")
	Optional<CatalogFile> findByFileKeyWithDetails(@Param("fileKey") String fileKey);

	/**
	 * Batched existence check: lets callers replace N individual
	 * {@link #findByFileKey} calls (one SELECT per file during an inventory scan)
	 * with a single {@code WHERE file_key IN (...)} query per batch of files.
	 */
	List<CatalogFile> findByFileKeyIn(List<String> fileKeys);

	/**
	 * Resolves the given public ids to their media files with the location eagerly
	 * loaded - used by the Duplicados deletion, which moves each file to quarantine
	 * and needs its current placement in the same read.
	 */
	@EntityGraph(attributePaths = { "location" })
	List<CatalogFile> findByPublicIdIn(Collection<UUID> publicIds);

	/**
	 * Lightweight existence check that returns only the {@code fileKey}s already
	 * present, not whole entities. Used by the parallel inventory to identify
	 * cached files in a short read transaction (so the connection is released)
	 * before the heavy extraction runs off any transaction.
	 */
	@Query("select mf.fileKey from CatalogFile mf where mf.fileKey in :fileKeys")
	List<String> findExistingFileKeys(@Param("fileKeys") List<String> fileKeys);

	/**
	 * Batched variant of {@link #findByFileKeyWithDetails}, used when force
	 * re-analysis needs the full entity graph (location/metadata/photo/video) for
	 * every already-known file in a batch.
	 */
	@EntityGraph(attributePaths = { "location", "metadata", "photo", "video" })
	@Query("select mf from CatalogFile mf where mf.fileKey in :fileKeys")
	List<CatalogFile> findByFileKeyInWithDetails(@Param("fileKeys") List<String> fileKeys);

	@Query("""
			select mf.id
			from CatalogFile mf
			join mf.location l
			left join mf.metadata m
			where mf.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  and (
			       lower(l.currentPath) = lower(:sourcePath)
			       or lower(l.currentPath) like lower(:descendantPattern) escape '\\'
			  )
			  and (
			       :captureDateNull is null
			       or (:captureDateNull = true and (m is null or m.captureDate is null))
			       or (:captureDateNull = false and m is not null and m.captureDate is not null)
			  )
			  and (:dateSource is null or m.dateSource = :dateSource)
			  and mf.id > :lastId
			order by mf.id
			""")
	List<Long> findIdsForMetadataRebuild(@Param("sourcePath") String sourcePath,
			@Param("descendantPattern") String descendantPattern, @Param("captureDateNull") Boolean captureDateNull,
			@Param("dateSource") DateSource dateSource, @Param("lastId") Long lastId, Pageable pageable);

	/**
	 * Same filter as {@link #findIdsForMetadataRebuild} without the keyset cursor,
	 * so the screen can show a total (and therefore a percentage and an estimate)
	 * before the first batch runs.
	 */
	@Query("""
			select count(mf.id)
			from CatalogFile mf
			join mf.location l
			left join mf.metadata m
			where mf.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  and (
			       lower(l.currentPath) = lower(:sourcePath)
			       or lower(l.currentPath) like lower(:descendantPattern) escape '\\'
			  )
			  and (
			       :captureDateNull is null
			       or (:captureDateNull = true and (m is null or m.captureDate is null))
			       or (:captureDateNull = false and m is not null and m.captureDate is not null)
			  )
			  and (:dateSource is null or m.dateSource = :dateSource)
			""")
	long countForMetadataRebuild(@Param("sourcePath") String sourcePath,
			@Param("descendantPattern") String descendantPattern, @Param("captureDateNull") Boolean captureDateNull,
			@Param("dateSource") DateSource dateSource);

	@Query("""
			select distinct mf
			from CatalogFile mf
			left join fetch mf.location
			left join fetch mf.metadata
			where mf.id in :ids
			order by mf.id
			""")
	List<CatalogFile> findForMetadataRebuildByIds(@Param("ids") List<Long> ids);
}