package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.DuplicateExclusionFile;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateExclusionFileView;

public interface DuplicateExclusionFileRepository extends JpaRepository<DuplicateExclusionFile, Long> {

	/**
	 * The one judgement standing about this file, whether or not it still bears on
	 * the content the file is holding.
	 *
	 * <p>
	 * The row rather than its existence: a caller deciding what to do about a
	 * second click has to know which bytes the first one was about, and asking
	 * only whether one exists is what made saying it again do nothing.
	 */
	Optional<DuplicateExclusionFile> findByCatalogFileId(Long catalogFileId);

	/**
	 * The files whose exclusion still bears on the content they are holding now.
	 *
	 * <p>
	 * The revision comparison is the whole point: an exclusion made about bytes
	 * that have since been replaced is left on record and stops being applied, so
	 * a picture nobody has judged is never hidden by a judgement about another
	 * one.
	 */
	@Query("""
			SELECT e.catalogFile.catalogFilePublicId
			FROM DuplicateExclusionFile e
			WHERE e.contentRevision = e.catalogFile.contentRevision
			""")
	List<UUID> findApplyingCatalogFilePublicIds();

	/**
	 * Every exclusion for the management list, applying or not - the screen shows
	 * what the user said, and whether it still bears on what is there.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateExclusionFileView(
					e.id, e.catalogFile.catalogFilePublicId, l.currentPath, e.createdAt,
					e.contentRevision = e.catalogFile.contentRevision)
			FROM DuplicateExclusionFile e
			LEFT JOIN e.catalogFile.location l
			ORDER BY e.createdAt DESC, e.id DESC
			""")
	List<DuplicateExclusionFileView> findAllViews();
}