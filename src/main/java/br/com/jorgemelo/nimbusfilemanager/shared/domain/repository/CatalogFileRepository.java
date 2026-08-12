package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository;

import java.time.Instant;
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

import br.com.jorgemelo.nimbusfilemanager.catalog.domain.repository.projection.CatalogExportRow;
import br.com.jorgemelo.nimbusfilemanager.media.domain.repository.projection.FolderInventorySummary;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.CatalogSignatureProjection;

public interface CatalogFileRepository extends JpaRepository<CatalogFile, Long> {

	/**
	 * Keyset-paginated flat projection for the catalog export: one row per
	 * {@code catalog_file} with its placement, ordered by id, starting after
	 * {@code lastId}. Keyset (not OFFSET) so exporting hundreds of thousands of
	 * rows stays linear instead of degrading on deep pages, and a DTO projection so
	 * no lazy association is touched while streaming outside a transaction.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.catalog.domain.repository.projection.CatalogExportRow(
				mf.id, mf.catalogFilePublicId, catalogFileName(loc.currentPath, CAST(loc.pathFlavor AS string)),
				mf.extension, mf.sizeBytes, mf.sha256, mf.mimeType, CAST(mf.fileType AS string),
				CAST(mf.lifecycleStatus AS string), mf.createdAt, mf.modifiedAt, mf.importedAt, loc.currentPath)
			FROM CatalogFile mf
			LEFT JOIN mf.location loc
			WHERE mf.id > :lastId
			ORDER BY mf.id
			""")
	List<CatalogExportRow> findCatalogExportRows(@Param("lastId") Long lastId, Pageable pageable);

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
	int deleteMissingBefore(@Param("cutoff") Instant cutoff);

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
			    SELECT l.catalog_file_id
			    FROM catalog_file_location l
			    WHERE l.path_flavor = :flavor
			      AND (l.path_key = canonicalize_catalog_path(:root, :flavor)
			           OR starts_with(l.path_key, canonicalize_catalog_path(:root, :flavor) || '/'))
			)
			""", nativeQuery = true)
	int deleteWithinLibrary(@Param("root") String root, @Param("flavor") String flavor);

	/**
	 * Whether anything at all is past the retention window - the cheap question the
	 * daily purge asks before queueing itself, so a quiet day leaves no execution
	 * behind. It decides whether to ask, never what to remove: that is settled when
	 * the purge runs.
	 */
	boolean existsByLifecycleStatusAndLifecycleChangedAtBefore(LifecycleStatus lifecycleStatus,
			Instant lifecycleChangedAt);

	/**
	 * The files a batch is about to write, with everything the mapper touches
	 * loaded in the same read - the identities having been decided by
	 * {@code CatalogPathMatcher} beforehand, from the paths.
	 */
	@EntityGraph(attributePaths = { "location", "metadata", "photo", "video" })
	List<CatalogFile> findWithDetailsByIdIn(Collection<Long> ids);

	/**
	 * Resolves the given public ids to their media files with the location eagerly
	 * loaded - used by the Duplicados deletion, which moves each file to quarantine
	 * and needs its current placement in the same read.
	 */
	@EntityGraph(attributePaths = { "location" })
	/**
	 * The files the user picked, by public id.
	 *
	 * <p>
	 * An array parameter because what bounds this list is a selection on a screen
	 * - "select all" over a large library included - and not anything structural.
	 * One placeholder per id is a ceiling at 65.535 that nobody would meet until
	 * the library was big enough for it to matter.
	 */
	@Query("""
			select mf from CatalogFile mf
			left join fetch mf.location
			where inArray(mf.catalogFilePublicId, :publicIds)
			""")
	List<CatalogFile> findByCatalogFilePublicIdIn(@Param("publicIds") UUID[] publicIds);

	/** The one file that identity names, for a caller holding a public id. */
	@Query("""
			select mf
			from CatalogFile mf
			where mf.catalogFilePublicId = :publicId
			""")
	Optional<CatalogFile> findByCatalogFilePublicId(@Param("publicId") UUID publicId);

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
			  and (
			       mf.lastAnalysis is null
			       or mf.lastAnalysis < :notAnalysedSince
			  )
			  and mf.id > :lastId
			order by mf.id
			""")
	List<Long> findIdsForMetadataRebuild(@Param("sourcePath") String sourcePath,
			@Param("descendantPattern") String descendantPattern, @Param("captureDateNull") Boolean captureDateNull,
			@Param("dateSource") DateSource dateSource, @Param("notAnalysedSince") Instant notAnalysedSince,
			@Param("lastId") Long lastId, Pageable pageable);

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
			  and (
			       mf.lastAnalysis is null
			       or mf.lastAnalysis < :notAnalysedSince
			  )
			""")
	long countForMetadataRebuild(@Param("sourcePath") String sourcePath,
			@Param("descendantPattern") String descendantPattern, @Param("captureDateNull") Boolean captureDateNull,
			@Param("dateSource") DateSource dateSource, @Param("notAnalysedSince") Instant notAnalysedSince);

	@Query("""
			select distinct mf
			from CatalogFile mf
			left join fetch mf.location
			left join fetch mf.metadata
			where mf.id in :ids
			order by mf.id
			""")
	List<CatalogFile> findForMetadataRebuildByIds(@Param("ids") List<Long> ids);

	/**
	 * Inventoried totals under a folder, for the properties dialog. Matching
	 * follows the project's path rule: the folder itself plus a descendant
	 * {@code like} whose pattern is built by {@code PathUtils} and read with an
	 * explicit {@code escape}, because a Windows path is full of backslashes and a
	 * file name may carry {@code %} or {@code _}.
	 */
	@Query("""
			select count(mf) as fileCount,
			       count(distinct l.currentFolder) as folderCount,
			       coalesce(sum(mf.sizeBytes), 0) as sizeBytes
			from CatalogFile mf
			join mf.location l
			where mf.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  and (
			       lower(l.currentFolder) = lower(:folder)
			       or lower(l.currentPath) like lower(:descendantPattern) escape '\\'
			  )
			""")
	FolderInventorySummary summarizeFolder(@Param("folder") String folder,
			@Param("descendantPattern") String descendantPattern);

	/**
	 * The catalog under a folder, reduced to a pair that moves whenever it moves.
	 *
	 * <p>
	 * The timestamp is the location's, not the file's: a file being moved is
	 * exactly what changes where a plan would send it, and moving one rewrites its
	 * location row. The count catches what a move does not - a file arriving, being
	 * quarantined or leaving.
	 *
	 * <p>
	 * Read when an organization plan is published, and compared when one is shown,
	 * so the screen can tell the user the library changed since - which is what
	 * keeps a preview and a later run from disagreeing silently.
	 */
	@Query("""
			select count(mf) as fileCount, max(l.updatedAt) as latestUpdate
			from CatalogFile mf
			join mf.location l
			where mf.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  and (
			       lower(l.currentFolder) = lower(:folder)
			       or lower(l.currentPath) like lower(:descendantPattern) escape '\\'
			  )
			""")
	CatalogSignatureProjection signatureUnder(@Param("folder") String folder,
			@Param("descendantPattern") String descendantPattern);

	/**
	 * Of these digests, the ones more than one catalogued file holds.
	 *
	 * <p>
	 * Asked when bytes are the only evidence available for where a file went. A
	 * digest only one file has can name that file; a digest two of them share
	 * names neither, and a photo library is full of exact duplicates - the same
	 * picture imported twice, a copy kept in a second folder. This is what stops
	 * the weakest evidence in the catalog from merging two of them into one.
	 */
	@Query(value = """
			SELECT m.sha256
			FROM catalog_file m
			WHERE m.lifecycle_status = 'ACTIVE' AND m.sha256 = ANY(CAST(:digests AS text[]))
			GROUP BY m.sha256
			HAVING count(*) > 1
			""", nativeQuery = true)
	List<String> digestsHeldMoreThanOnce(@Param("digests") String[] digests);
}