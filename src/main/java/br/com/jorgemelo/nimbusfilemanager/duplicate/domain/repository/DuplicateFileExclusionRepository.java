package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.DuplicateFileExclusion;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateFileExclusionView;

public interface DuplicateFileExclusionRepository extends JpaRepository<DuplicateFileExclusion, Long> {

	boolean existsByPublicId(UUID publicId);

	@Query("SELECT e.publicId FROM DuplicateFileExclusion e")
	List<UUID> findAllPublicIds();

	/**
	 * Every file exclusion joined to its current path for the management list. The
	 * FK cascade keeps this consistent - an exclusion cannot outlive its
	 * catalog_file - so a plain inner join never drops a row.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateFileExclusionView(e.id, e.publicId, l.currentPath, e.createdAt)
			FROM DuplicateFileExclusion e, CatalogFile m
			LEFT JOIN m.location l
			WHERE m.publicId = e.publicId
			ORDER BY e.createdAt DESC, e.id DESC
			""")
	List<DuplicateFileExclusionView> findAllViews();
}