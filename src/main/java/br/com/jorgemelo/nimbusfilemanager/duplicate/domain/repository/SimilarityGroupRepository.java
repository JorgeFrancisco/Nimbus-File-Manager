package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGroup;

public interface SimilarityGroupRepository extends JpaRepository<SimilarityGroup, Long> {

	/**
	 * One page of groups, ordered by the position the analysis froze.
	 *
	 * <p>
	 * This is what the durable result buys: the screen asks the database for
	 * twenty groups instead of deserializing an entire analysis to show twenty.
	 * The order comes from a stored column rather than from re-sorting by wasted
	 * bytes, because ties in that value would let two requests disagree about which
	 * group sits on a page boundary.
	 */
	Page<SimilarityGroup> findByGroupingIdOrderByPositionAsc(Long groupingId, Pageable pageable);

	List<SimilarityGroup> findByGroupingIdInOrderByPositionAsc(List<Long> groupingIds);
}