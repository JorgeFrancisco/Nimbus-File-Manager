package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateFileRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateFileWithShaRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateGroupRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateSummaryProjection;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;

public interface DuplicateRepository extends JpaRepository<CatalogFile, Long> {

	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateGroupRawResponse(
				m.sha256,
				COUNT(m),
				SUM(m.sizeBytes),
				SUM(m.sizeBytes) - MIN(m.sizeBytes)
			)
			FROM CatalogFile m
			LEFT JOIN m.location l
			WHERE m.sha256 IS NOT NULL
			  AND TRIM(m.sha256) <> ''
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  AND m.fileType IN :types
			  AND NOT EXISTS (SELECT 1 FROM DuplicateFileExclusion fe WHERE fe.publicId = m.publicId)
			  AND NOT EXISTS (SELECT 1 FROM DuplicateFolderExclusion fo
			                  WHERE REPLACE(l.currentFolder, '\\', '/') = fo.folderPath
			                     OR REPLACE(l.currentFolder, '\\', '/') LIKE CONCAT(REPLACE(REPLACE(fo.folderPath, '%', '\\%'), '_', '\\_'), '/%') ESCAPE '\\')
			GROUP BY m.sha256
			HAVING COUNT(m) > 1
			ORDER BY SUM(m.sizeBytes) - MIN(m.sizeBytes) DESC
			""")
	Page<DuplicateGroupRawResponse> findDuplicateGroups(@Param("types") Collection<FileType> types, Pageable pageable);

	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateFileRawResponse(
				m.publicId,
				m.fileName,
				m.extension,
				CAST(m.fileType AS string),
				m.sizeBytes,
				l.currentPath,
				l.currentFolder,
				m.modifiedAt
			)
			FROM CatalogFile m
			LEFT JOIN m.location l
			WHERE m.sha256 = :sha256
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  AND NOT EXISTS (SELECT 1 FROM DuplicateFileExclusion fe WHERE fe.publicId = m.publicId)
			  AND NOT EXISTS (SELECT 1 FROM DuplicateFolderExclusion fo
			                  WHERE REPLACE(l.currentFolder, '\\', '/') = fo.folderPath
			                     OR REPLACE(l.currentFolder, '\\', '/') LIKE CONCAT(REPLACE(REPLACE(fo.folderPath, '%', '\\%'), '_', '\\_'), '/%') ESCAPE '\\')
			ORDER BY l.currentFolder ASC, m.fileName ASC, m.id ASC
			""")
	List<DuplicateFileRawResponse> findDuplicateFiles(String sha256);

	/**
	 * Bulk variant of {@link #findDuplicateFiles(String)} used to load the files of
	 * every group in a page with a single query.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateFileWithShaRawResponse(
				m.sha256,
				m.publicId,
				m.fileName,
				m.extension,
				CAST(m.fileType AS string),
				m.sizeBytes,
				l.currentPath,
				l.currentFolder,
				m.modifiedAt
			)
			FROM CatalogFile m
			LEFT JOIN m.location l
			WHERE m.sha256 IN :sha256List
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  AND m.fileType IN :types
			  AND NOT EXISTS (SELECT 1 FROM DuplicateFileExclusion fe WHERE fe.publicId = m.publicId)
			  AND NOT EXISTS (SELECT 1 FROM DuplicateFolderExclusion fo
			                  WHERE REPLACE(l.currentFolder, '\\', '/') = fo.folderPath
			                     OR REPLACE(l.currentFolder, '\\', '/') LIKE CONCAT(REPLACE(REPLACE(fo.folderPath, '%', '\\%'), '_', '\\_'), '/%') ESCAPE '\\')
			ORDER BY m.sha256 ASC, l.currentFolder ASC, m.fileName ASC, m.id ASC
			""")
	List<DuplicateFileWithShaRawResponse> findDuplicateFilesForShas(@Param("sha256List") List<String> sha256List,
			@Param("types") Collection<FileType> types);

	@Query(value = """
			SELECT
				COUNT(*) AS groups,
				COALESCE(SUM(files), 0) AS duplicatedFiles,
				COALESCE(SUM(total_size_bytes), 0) AS totalSizeBytes,
				COALESCE(SUM(wasted_size_bytes), 0) AS wastedSizeBytes
			FROM (
				SELECT
					mf.sha256,
					COUNT(*) AS files,
					SUM(mf.size_bytes) AS total_size_bytes,
					SUM(mf.size_bytes) - MIN(mf.size_bytes) AS wasted_size_bytes
				FROM catalog_file mf
				JOIN catalog_file_location l ON l.catalog_file_id = mf.id
				WHERE mf.sha256 IS NOT NULL
				  AND TRIM(mf.sha256) <> ''
				  AND mf.lifecycle_status = 'ACTIVE'
				  AND NOT EXISTS (SELECT 1 FROM duplicate_file_exclusion fe WHERE fe.public_id = mf.public_id)
				  AND NOT EXISTS (SELECT 1 FROM duplicate_folder_exclusion fo
				                  WHERE REPLACE(l.current_folder, '\\', '/') = fo.folder_path
				                     OR REPLACE(l.current_folder, '\\', '/') LIKE REPLACE(REPLACE(fo.folder_path, '%', '\\%'), '_', '\\_') || '/%' ESCAPE '\\')
				GROUP BY mf.sha256
				HAVING COUNT(*) > 1
			) g
			""",
			nativeQuery = true)
	DuplicateSummaryProjection summary();
}