package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintFailure;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.FingerprintFailureDetail;

/**
 * Operational per-item failures. Only items that failed appear here; a
 * successful item has a {@code media_fingerprint} row and none here.
 */
public interface FingerprintFailureRepository extends JpaRepository<FingerprintFailure, Long> {

	Optional<FingerprintFailure> findByCatalogFileIdAndKindAndAlgorithm(Long catalogFileId, FingerprintKind kind,
			String algorithm);

	/**
	 * Exhausted processing errors, excluding known unsupported physical formats.
	 */
	@Query("""
			SELECT count(fe) FROM FingerprintFailure fe
			JOIN CatalogFile m ON m.id = fe.catalogFileId
			WHERE fe.kind = :kind AND fe.algorithm = :algorithm AND fe.attempts >= :attempts
			  AND m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.PHOTO
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			""")
	long countExhaustedFailures(@Param("kind") FingerprintKind kind, @Param("algorithm") String algorithm,
			@Param("attempts") int attempts);

	/**
	 * Exhausted failures with their current physical path for the UI details modal.
	 */
	@Transactional(readOnly = true)
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.FingerprintFailureDetail(
				l.currentPath, fe.reason, fe.lastError)
			FROM FingerprintFailure fe
			JOIN CatalogFile m ON m.id = fe.catalogFileId
			JOIN m.location l
			WHERE fe.kind = :kind AND fe.algorithm = :algorithm AND fe.attempts >= :attempts
			  AND m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.PHOTO
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			ORDER BY l.currentPath ASC, fe.id ASC
			""")
	List<FingerprintFailureDetail> findExhaustedWithPath(@Param("kind") FingerprintKind kind,
			@Param("algorithm") String algorithm, @Param("attempts") int attempts);

	/** Video counterpart of {@link #countExhaustedFailures}, filtered to videos. */
	@Query("""
			SELECT count(fe) FROM FingerprintFailure fe
			JOIN CatalogFile m ON m.id = fe.catalogFileId
			WHERE fe.kind = :kind AND fe.algorithm = :algorithm AND fe.attempts >= :attempts
			  AND m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.VIDEO
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			""")
	long countExhaustedVideoFailures(@Param("kind") FingerprintKind kind, @Param("algorithm") String algorithm,
			@Param("attempts") int attempts);

	/** Video counterpart of {@link #findExhaustedWithPath}, filtered to videos. */
	@Transactional(readOnly = true)
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.FingerprintFailureDetail(
				l.currentPath, fe.reason, fe.lastError)
			FROM FingerprintFailure fe
			JOIN CatalogFile m ON m.id = fe.catalogFileId
			JOIN m.location l
			WHERE fe.kind = :kind AND fe.algorithm = :algorithm AND fe.attempts >= :attempts
			  AND m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.VIDEO
			  AND m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			ORDER BY l.currentPath ASC, fe.id ASC
			""")
	List<FingerprintFailureDetail> findExhaustedVideoWithPath(@Param("kind") FingerprintKind kind,
			@Param("algorithm") String algorithm, @Param("attempts") int attempts);

	@Transactional
	void deleteByCatalogFileIdAndKindAndAlgorithm(Long catalogFileId, FingerprintKind kind, String algorithm);

	/**
	 * Manual retry: clears failures so exhausted items return to the pending queue.
	 */
	@Transactional
	long deleteByKindAndAlgorithm(FingerprintKind kind, String algorithm);

	/**
	 * Manual retry clears only what a retry can change. A terminal reason - blank
	 * bytes, a format the decoder never read - stays, so the button never promises
	 * to fix a file nothing can fix.
	 */
	@Transactional
	@Modifying
	@Query("""
			DELETE FROM FingerprintFailure fe
			WHERE fe.kind = :kind AND fe.algorithm = :algorithm AND fe.reason = :reason
			""")
	long deleteRetryableByKindAndAlgorithm(@Param("kind") FingerprintKind kind, @Param("algorithm") String algorithm,
			@Param("reason") FingerprintFailureReason reason);
}