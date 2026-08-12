package br.com.jorgemelo.nimbusfilemanager.organization.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection.OrganizationCandidate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;

public interface OrganizationCandidateRepository extends JpaRepository<CatalogFile, Long> {

	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection.OrganizationCandidate(
			    mf.id,
			    mf.catalogFilePublicId,
			    catalogFileName(ml.currentPath, CAST(ml.pathFlavor AS string)),
			    mf.extension,
			    mf.fileType,
			    mf.sizeBytes,
			    ml.currentPath,
			    ml.currentFolder,
			    m.year,
			    m.month,
			    m.day,
			    m.yearMonth,
			    m.captureDate,
			    m.category,
			    m.subcategory
			)
			FROM CatalogFile mf
			JOIN mf.location ml
			LEFT JOIN mf.metadata m
			WHERE mf.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  AND (
			       :sourcePath IS NULL
			       OR LOWER(ml.currentFolder) = LOWER(:sourcePath)
			       OR LOWER(ml.currentFolder) LIKE LOWER(:descendantPattern) ESCAPE '\\'
			  )
			ORDER BY m.yearMonth ASC, m.day ASC, m.subcategory ASC,
			         catalogFileName(ml.currentPath, CAST(ml.pathFlavor AS string)) ASC
			""")
	Page<OrganizationCandidate> findCandidates(@Param("sourcePath") String sourcePath,
			@Param("descendantPattern") String descendantPattern, Pageable pageable);
}