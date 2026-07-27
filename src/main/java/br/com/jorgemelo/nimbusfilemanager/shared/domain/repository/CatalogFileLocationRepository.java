package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.FileExplorerLocationProjection;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.MediaLocationReconcileProjection;

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
	List<FileExplorerLocationProjection> findActiveByCurrentFolder(
			@Param("currentFolder") String currentFolder);

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
}