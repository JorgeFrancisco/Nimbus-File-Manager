package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGroupMember;

public interface SimilarityGroupMemberRepository extends JpaRepository<SimilarityGroupMember, Long> {

	/**
	 * The members of the groups on one page, fetched in a single query rather than
	 * one per group - twenty groups of five files is twenty round trips otherwise,
	 * on a screen that paginates.
	 */
	List<SimilarityGroupMember> findByGroupIdInOrderByGroupIdAscPositionAsc(List<Long> groupIds);
}