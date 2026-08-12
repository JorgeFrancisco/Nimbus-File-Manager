package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.jorgemelo.nimbusfilemanager.media.domain.repository.projection.FileExplorerLocationProjection;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection.MediaLocationReconcileProjection;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FilesystemIdentityKind;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.CatalogPathMatchRow;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.FolderPlacementRow;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.KnownContentBatchRow;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.KnownContentRow;

public interface CatalogFileLocationRepository extends JpaRepository<CatalogFileLocation, Long> {

	Optional<CatalogFileLocation> findByCatalogFileIdAndCurrentPath(Long catalogFileId, String currentPath);

	/**
	 * Every catalogued place carrying this filesystem identity.
	 *
	 * <p>
	 * A list and never one, because the schema deliberately allows two: a hard
	 * link is one object with two names, so an identity that names two rows is
	 * correct data rather than a fault. Returning one - whichever one - would let
	 * a caller act on a guess, so the count is the caller's to see and to refuse.
	 */
	@Query("""
			SELECT l
			FROM CatalogFileLocation l
			WHERE l.filesystemIdentityKind = :kind
			  AND l.filesystemIdentityScope = :scope
			  AND l.filesystemIdentityValue = :value
			""")
	List<CatalogFileLocation> findByFilesystemIdentity(@Param("kind") FilesystemIdentityKind kind,
			@Param("scope") String scope, @Param("value") String value);

	/**
	 * The file occupying this path right now, if one does.
	 *
	 * <p>
	 * "Occupying" is the whole distinction: a path a file merely used to be at is
	 * not taken, and this is the question to ask before writing a file there or
	 * before acting on what is there. The canonical form is computed by the same
	 * database function that filled {@code path_key}, because Java computing it a
	 * second time is how two spellings of one rule start disagreeing.
	 */
	@Query("""
			SELECT l.catalogFile
			FROM CatalogFileLocation l
			WHERE l.pathKey = canonicalPath(:path, :flavor)
			  AND l.catalogFile.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			""")
	Optional<CatalogFile> findPresentByPath(@Param("path") String path, @Param("flavor") String flavor);

	/**
	 * What the catalog believes the file at this path contains, in one read.
	 *
	 * <p>
	 * Present files only, for the same reason {@link #findPresentByPath} says: a
	 * path a file merely used to be at is not what the operating system just
	 * reported a write to.
	 */
	@Query("""
			SELECT l.catalogFile.id AS catalogFileId, l.catalogFile.sha256 AS sha256,
			       l.catalogFile.sizeBytes AS sizeBytes, l.catalogFile.modifiedAt AS modifiedAt,
			       l.catalogFile.contentRevision AS contentRevision,
			       l.filesystemIdentityKind AS filesystemIdentityKind,
			       l.filesystemIdentityScope AS filesystemIdentityScope,
			       l.filesystemIdentityValue AS filesystemIdentityValue
			FROM CatalogFileLocation l
			WHERE l.pathKey = canonicalPath(:path, :flavor)
			  AND l.catalogFile.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			""")
	Optional<KnownContentRow> findKnownContentByPath(@Param("path") String path, @Param("flavor") String flavor);

	/**
	 * The same answer for a whole batch, in one round trip.
	 *
	 * <p>
	 * A walk of a library asks this once per chunk rather than once per file: the
	 * per-file question is cheap only until it is asked a hundred and fifty
	 * thousand times.
	 */
	@Query(value = """
			SELECT l.catalog_file_id AS catalogFileId, m.sha256 AS sha256, m.size_bytes AS sizeBytes,
			       m.modified_at AS modifiedAt, m.content_revision AS contentRevision,
			       i.input_path AS inputPath,
			       l.filesystem_identity_kind AS filesystemIdentityKind,
			       l.filesystem_identity_scope AS filesystemIdentityScope,
			       l.filesystem_identity_value AS filesystemIdentityValue
			FROM unnest(CAST(:paths AS text[])) AS i(input_path)
			JOIN catalog_file_location l
			  ON l.path_flavor = :flavor
			 AND l.path_key = canonicalize_catalog_path(i.input_path, :flavor)
			JOIN catalog_file m ON m.id = l.catalog_file_id
			WHERE m.lifecycle_status = 'ACTIVE'
			""", nativeQuery = true)
	List<KnownContentBatchRow> findKnownContentByPaths(@Param("paths") String[] paths,
			@Param("flavor") String flavor);

	/**
	 * Every file whose last known place is this path, present or not.
	 *
	 * <p>
	 * A list rather than one, and deliberately: a file that went missing keeps the
	 * path it was last seen at, so a newcomer at that path and the file that left
	 * it both name it. Ordered with the present one first, because a caller
	 * re-finding a file wants the one that is actually there before the one that
	 * only used to be.
	 */
	@Query("""
			SELECT l.catalogFile
			FROM CatalogFileLocation l
			WHERE l.pathKey = canonicalPath(:path, :flavor)
			ORDER BY CASE WHEN l.catalogFile.lifecycleStatus
			              = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			         THEN 0 ELSE 1 END, l.catalogFile.id ASC
			""")
	List<CatalogFile> findLastKnownByPath(@Param("path") String path, @Param("flavor") String flavor);

	/**
	 * Every file whose last known place is one of the given paths - one query for
	 * the whole batch an inventory pass is holding.
	 *
	 * <p>
	 * The canonical form of each scanned path is computed by the database, by the
	 * same function that filled {@code path_key}. That is the only way the two can
	 * be guaranteed to agree: a scan that reads {@code D:\FOTOS\a.JPG} has to find
	 * the row written as {@code D:\Fotos\A.jpg}, and deciding that in Java would be
	 * the canonicalization rule living in two places.
	 *
	 * <p>
	 * Native, and deliberately: {@code unnest} turns the batch into rows the
	 * planner can drive the {@code path_key} index from, which an {@code IN} list
	 * of canonicalized values could not do without Java computing them first.
	 *
	 * <p>
	 * The flavor is matched as well as passed in, and not for symmetry: a Windows
	 * path is folded to lower case and a POSIX one is not, so two rows under
	 * different rules can spell the same key while naming different places. Keys
	 * are only comparable under the rules that produced them.
	 *
	 * <p>
	 * More than one row may come back for the same input. That is not a defect to
	 * be filtered here - it is the question the caller has to answer.
	 */
	@Query(value = """
			SELECT i.input_path AS inputPath,
			       m.id AS catalogFileId,
			       m.lifecycle_status AS lifecycleStatus
			FROM unnest(CAST(:paths AS text[])) AS i(input_path)
			JOIN catalog_file_location l
			  ON l.path_flavor = :flavor
			 AND l.path_key = canonicalize_catalog_path(i.input_path, :flavor)
			JOIN catalog_file m ON m.id = l.catalog_file_id
			""", nativeQuery = true)
	List<CatalogPathMatchRow> findLastKnownByPaths(@Param("paths") String[] paths,
			@Param("flavor") String flavor);

	/**
	 * Every catalogued file under a folder, present or not, in the order the write
	 * door locks them.
	 *
	 * <p>
	 * Ordered by id because the caller hands this list straight back as the batch
	 * to relocate, and the function locks rows in that order to stay clear of a
	 * single-file move running beside it.
	 *
	 * <p>
	 * Descendants only, and by canonical key rather than by {@code LIKE}: a Windows
	 * path is made of backslashes, which {@code LIKE} reads as escapes, and file
	 * names are full of its wildcards. The flavor is matched because two spellings
	 * read under different rules can produce the same key while naming folders on
	 * different machines.
	 */
	@Query(value = """
			SELECT l.catalog_file_id AS catalogFileId, l.current_path AS currentPath
			FROM catalog_file_location l
			WHERE l.path_flavor = :flavor
			  AND starts_with(l.path_key, canonicalize_catalog_path(:folder, :flavor) || '/')
			ORDER BY l.catalog_file_id
			""", nativeQuery = true)
	List<FolderPlacementRow> findPlacementsUnderFolder(@Param("folder") String folder,
			@Param("flavor") String flavor);

	/**
	 * The name is asked of the database rather than cut out of the path here,
	 * because the screen is ordered by it: sorting in Java would only reorder the
	 * rows this query already chose, which is a different list whenever there are
	 * more than fit on a page.
	 */
	@Query("""
			select l.catalogFile.id as catalogFileId,
			       l.catalogFile.catalogFilePublicId as publicId,
			       catalogFileName(l.currentPath, cast(l.pathFlavor as string)) as fileName,
			       l.catalogFile.fileType as fileType,
			       l.catalogFile.sizeBytes as sizeBytes,
			       l.currentPath as currentPath
			from CatalogFileLocation l
			where l.catalogFile.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  and lower(l.currentFolder) = lower(:currentFolder)
			order by catalogFileName(l.currentPath, cast(l.pathFlavor as string)) asc, l.catalogFile.id asc
			""")
	List<FileExplorerLocationProjection> findActiveByCurrentFolder(@Param("currentFolder") String currentFolder);

	/**
	 * One page of reconcile candidates, walked by key: the caller passes the last
	 * id it read and gets what comes after it.
	 *
	 * <p>
	 * Paging by page number made the database skip every row of every earlier
	 * page to serve the next one, so the last page of a library with a hundred
	 * thousand files cost a full scan while the first cost nothing - and the
	 * reconcile runs beside an inventory over the same tables. Keyed this way
	 * every page costs the same.
	 *
	 * <p>
	 * Keyed on the file rather than on the placement, because the file is what
	 * this hands back and therefore the only key a caller can return. The two
	 * run together in a catalog built in one pass and drift apart in any other -
	 * a file catalogued before it had a placement, a placement rewritten - and
	 * from then on a walk keyed on one and resumed with the other skips rows it
	 * never reports, which in a reconcile is a file never examined at all.
	 */
	@Query("""
			select l.catalogFile.id as catalogFileId,
			       l.currentPath as currentPath,
			       l.catalogFile.sizeBytes as sizeBytes,
			       l.catalogFile.modifiedAt as modifiedAt
			from CatalogFileLocation l
			where l.catalogFile.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  and l.catalogFile.id > :afterId
			  and (
			       lower(l.currentPath) = lower(:sourcePath)
			       or lower(l.currentPath) like lower(:descendantPattern) escape '\\'
			  )
			order by l.catalogFile.id
			""")
	List<MediaLocationReconcileProjection> findForReconcile(@Param("sourcePath") String sourcePath,
			@Param("descendantPattern") String descendantPattern, @Param("afterId") long afterId, Limit limit);

	/**
	 * The same page, for a reconcile that is not walking subfolders: only the
	 * files whose containing folder <em>is</em> this one.
	 *
	 * <p>
	 * It exists because the two sides of a reconcile have to describe the same
	 * universe. A shallow pass lists one folder on disk; asking the catalog for
	 * that folder and everything under it would hand the comparison a set of rows
	 * the disk scan was never going to contain, and every one of them would be
	 * concluded missing. Matching on the stored folder rather than on a path
	 * prefix is also what keeps this free of the backslash-and-wildcard problem a
	 * {@code LIKE} would bring.
	 */
	@Query("""
			select l.catalogFile.id as catalogFileId,
			       l.currentPath as currentPath,
			       l.catalogFile.sizeBytes as sizeBytes,
			       l.catalogFile.modifiedAt as modifiedAt
			from CatalogFileLocation l
			where l.catalogFile.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  and l.catalogFile.id > :afterId
			  and lower(l.currentFolder) = lower(:sourcePath)
			order by l.catalogFile.id
			""")
	List<MediaLocationReconcileProjection> findForShallowReconcile(@Param("sourcePath") String sourcePath,
			@Param("afterId") long afterId, Limit limit);

}