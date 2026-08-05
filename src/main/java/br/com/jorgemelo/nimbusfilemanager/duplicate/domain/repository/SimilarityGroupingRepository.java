package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGrouping;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;

public interface SimilarityGroupingRepository extends JpaRepository<SimilarityGrouping, Long> {

	/**
	 * The published answer of a family, which is the only grouping a screen ever
	 * reads. BUILDING is invisible by construction here rather than by a filter
	 * somewhere in the service: a half-written result must not be reachable even by
	 * accident.
	 */
	@Query("""
			SELECT g FROM SimilarityGrouping g
			WHERE g.mediaType = :mediaType AND g.algorithmId = :algorithmId
			  AND g.groupingVersion = :groupingVersion AND g.parametersDigest = :parametersDigest
			  AND g.status = br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus.ACTIVE
			""")
	Optional<SimilarityGrouping> findActive(@Param("mediaType") FileType mediaType,
			@Param("algorithmId") String algorithmId, @Param("groupingVersion") int groupingVersion,
			@Param("parametersDigest") String parametersDigest);

	/**
	 * Retires the current answer of a family. Runs in the same short transaction
	 * that promotes its successor, so there is never an instant with none - and
	 * never one with two, which the partial unique index refuses outright.
	 *
	 * @return how many rows were retired, which tells the publication whether it
	 * replaced anything
	 */
	@Modifying
	@Query("""
			UPDATE SimilarityGrouping g
			   SET g.status = br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus.SUPERSEDED
			 WHERE g.mediaType = :mediaType AND g.algorithmId = :algorithmId
			   AND g.groupingVersion = :groupingVersion AND g.parametersDigest = :parametersDigest
			   AND g.status = br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus.ACTIVE
			""")
	int supersedeActive(@Param("mediaType") FileType mediaType, @Param("algorithmId") String algorithmId,
			@Param("groupingVersion") int groupingVersion, @Param("parametersDigest") String parametersDigest);

	/**
	 * Retires one named answer, and only if it is still the answer.
	 *
	 * <p>
	 * The family-wide {@link #supersedeActive} retires whatever is current;
	 * this one retires the row a result was <em>derived</em> from, which is the
	 * question an incremental run has to ask. Naming the id makes the check and
	 * the write a single statement: a publication that slipped in first has
	 * already moved that row out of ACTIVE, so this updates nothing and the caller
	 * learns it was overtaken instead of retiring a newer answer on top of an
	 * older one's conclusions.
	 *
	 * @return 1 when it was retired, 0 when it was no longer the current answer
	 */
	@Modifying
	@Query("""
			UPDATE SimilarityGrouping g
			   SET g.status = br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus.SUPERSEDED
			 WHERE g.id = :id
			   AND g.status = br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus.ACTIVE
			""")
	int supersede(@Param("id") Long id);

	@Modifying
	@Query("""
			UPDATE SimilarityGrouping g
			   SET g.status = br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus.ACTIVE,
			       g.publishedAt = :publishedAt
			 WHERE g.id = :id
			   AND g.status = br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus.BUILDING
			""")
	int publish(@Param("id") Long id, @Param("publishedAt") LocalDateTime publishedAt);

	/**
	 * Groupings left mid-build by a worker that died. Nothing reads them and
	 * nothing promotes them - a partial result is never published - so the only
	 * thing to do with one is delete it, once it is old enough that no live worker
	 * could still be filling it in.
	 */
	List<SimilarityGrouping> findByStatusAndComputedAtBefore(GroupingStatus status, LocalDateTime before);

	List<SimilarityGrouping> findByStatusOrderByComputedAtAsc(GroupingStatus status);
}