package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.jorgemelo.nimbusfilemanager.media.domain.repository.projection.FileExplorerLocationProjection;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection.MediaLocationReconcileProjection;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;

public interface CatalogFileLocationRepository extends JpaRepository<CatalogFileLocation, Long> {

	Optional<CatalogFileLocation> findByCatalogFileIdAndCurrentPath(Long catalogFileId, String currentPath);

	@Query("""
			select l.catalogFile.id as catalogFileId,
			       l.catalogFile.publicId as publicId,
			       l.catalogFile.fileName as fileName,
			       l.catalogFile.fileType as fileType,
			       l.catalogFile.sizeBytes as sizeBytes,
			       l.currentPath as currentPath
			from CatalogFileLocation l
			where l.catalogFile.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  and lower(l.currentFolder) = lower(:currentFolder)
			order by l.catalogFile.fileName asc, l.catalogFile.id asc
			""")
	List<FileExplorerLocationProjection> findActiveByCurrentFolder(@Param("currentFolder") String currentFolder);

	/**
	 * One page of reconcile candidates, walked by key: the caller passes the last
	 * id it read and gets what comes after it. Paging by page number made the
	 * database skip every row of every earlier page to serve the next one, so the
	 * last page of a library with a hundred thousand files cost a full scan while
	 * the first cost nothing - and the reconcile runs beside an inventory over the
	 * same tables. Keyed this way every page costs the same.
	 */
	@Query("""
			select l.catalogFile.id as catalogFileId,
			       l.catalogFile.fileKey as fileKey,
			       l.currentPath as currentPath
			from CatalogFileLocation l
			where l.catalogFile.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  and l.id > :afterId
			  and (
			       lower(l.currentPath) = lower(:sourcePath)
			       or lower(l.currentPath) like lower(:descendantPattern) escape '\\'
			  )
			order by l.id
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
			       l.catalogFile.fileKey as fileKey,
			       l.currentPath as currentPath
			from CatalogFileLocation l
			where l.catalogFile.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  and l.id > :afterId
			  and lower(l.currentFolder) = lower(:sourcePath)
			order by l.id
			""")
	List<MediaLocationReconcileProjection> findForShallowReconcile(@Param("sourcePath") String sourcePath,
			@Param("afterId") long afterId, Limit limit);

	/**
	 * The placement side of a folder rename: where each file is now, and which
	 * folder it is now in.
	 *
	 * <p>
	 * The folder needs the case expression because the files sitting directly in
	 * the renamed folder have it stored without a trailing separator, so the
	 * prefix that matches their path does not match their folder. Everything
	 * deeper does match, and is rewritten the same way as the path.
	 *
	 * <p>
	 * {@code original_path} and {@code original_folder} are left alone on purpose:
	 * they say where the file was first found, which a rename does not change.
	 *
	 * @return how many placements moved with the folder
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			UPDATE catalog_file_location
			   SET current_path = :newPrefix || substr(current_path, length(:oldPrefix) + 1),
			       current_folder = CASE WHEN lower(current_folder) = lower(:oldFolder) THEN :newFolder
			                             ELSE :newPrefix || substr(current_folder, length(:oldPrefix) + 1) END,
			       updated_at = :changedAt
			 WHERE lower(left(current_path, length(:oldPrefix))) = lower(:oldPrefix)
			""", nativeQuery = true)
	int repointLocations(@Param("oldFolder") String oldFolder, @Param("newFolder") String newFolder,
			@Param("oldPrefix") String oldPrefix, @Param("newPrefix") String newPrefix,
			@Param("changedAt") LocalDateTime changedAt);
}