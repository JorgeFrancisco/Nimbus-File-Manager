package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityComposition;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFamily;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGroupMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGrouping;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityGroupMemberRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityGroupRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityGroupingRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Writing an analysis down, and making it the answer - two things, deliberately
 * in different transactions.
 *
 * <p>
 * The writing takes as long as the result is big: thousands of groups and their
 * members. The switch takes microseconds. Holding one transaction across both
 * would keep a write lock on the family for the whole insertion, which is the
 * shape that turns a slow analysis into a blocked screen.
 *
 * <p>
 * So the result is built as BUILDING - invisible to every query the screen makes
 * - and only when it is complete does a short transaction retire the previous
 * answer and promote this one. A crash anywhere before that leaves a BUILDING
 * row nobody reads and the previous ACTIVE exactly where it was, which is the
 * behaviour the product needs: a failed recalculation must never cost the user
 * the analysis they already had.
 */
@Slf4j
@Component
class SimilarityPublisher {

	/** Rows per insert, so a large result does not become one huge statement. */
	private static final int BATCH_SIZE = 500;

	private final SimilarityGroupingRepository groupingRepository;
	private final SimilarityGroupRepository groupRepository;
	private final SimilarityGroupMemberRepository memberRepository;
	private final Clock clock;

	SimilarityPublisher(SimilarityGroupingRepository groupingRepository, SimilarityGroupRepository groupRepository,
			SimilarityGroupMemberRepository memberRepository, Clock clock) {
		this.groupingRepository = groupingRepository;
		this.groupRepository = groupRepository;
		this.memberRepository = memberRepository;
		this.clock = clock;
	}

	/**
	 * Writes the analysis as BUILDING and returns it. Nothing reads a grouping in
	 * this state, so the insertion can take as long as it takes.
	 */
	@Transactional
	SimilarityGrouping build(SimilarityAnalysisResult result, Long executionId) {
		SimilarityFamily family = result.family();

		SimilarityComposition composition = result.composition();

		SimilarityGrouping grouping = groupingRepository.save(SimilarityGrouping.builder()
				.mediaType(family.mediaType()).algorithmId(family.algorithmId())
				.groupingVersion(family.groupingVersion()).parametersDigest(family.parametersDigest())
				.compositionDigest(composition.digest()).eligibleCount(composition.eligibleCount())
				.analyzedCount(composition.analyzedCount()).candidateLimit(composition.candidateLimit())
				.selectionPolicy(composition.selectionPolicy()).status(GroupingStatus.BUILDING)
				.computedAt(LocalDateTime.now(clock)).executionId(executionId)
				.groupCount(result.groups().size()).memberCount(result.memberCount()).build());

		writeGroups(grouping.getId(), result.groups());

		return grouping;
	}

	/**
	 * Retires the family's current answer and promotes this one, in one short
	 * transaction of its own.
	 *
	 * <p>
	 * {@code REQUIRES_NEW} because the caller is a worker handler whose transaction
	 * spans work this one must not wait for. The partial unique index is what makes
	 * it safe against a second worker doing the same thing at the same instant:
	 * whoever loses gets a constraint violation instead of publishing a second
	 * current answer. Deduplication should have prevented the race; the index is
	 * what happens when it did not.
	 *
	 * @return whether this grouping became the answer
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	boolean publish(SimilarityGrouping grouping) {
		int retired = groupingRepository.supersedeActive(grouping.getMediaType(), grouping.getAlgorithmId(),
				grouping.getGroupingVersion(), grouping.getParametersDigest());

		int published = groupingRepository.publish(grouping.getId(), LocalDateTime.now(clock));

		if (published == 0) {
			// Not BUILDING any more: something else already decided this grouping's
			// fate - a reclaim cleaned it up, or it was published twice.
			log.warn("Similarity grouping {} was no longer building and was not published", grouping.getPublicId());

			return false;
		}

		log.info("Published similarity grouping {} for {} ({} groups, {} of {} files analysed), retiring {} previous",
				grouping.getPublicId(), grouping.getMediaType(), grouping.getGroupCount(), grouping.getAnalyzedCount(),
				grouping.getEligibleCount(), retired);

		return true;
	}

	/**
	 * Groups and members in batches rather than one round trip each: a library
	 * with thousands of similar photos is thousands of groups, and the analysis
	 * that produced them took minutes precisely so nobody would have to wait for
	 * it again.
	 */
	private void writeGroups(Long groupingId, List<AnalyzedGroup> groups) {
		List<SimilarityGroup> rows = new ArrayList<>(groups.size());

		for (int position = 0; position < groups.size(); position++) {
			AnalyzedGroup group = groups.get(position);

			rows.add(SimilarityGroup.builder().groupingId(groupingId).similarityPercent(group.similarityPercent())
					.fileCount(group.members().size()).wastedBytes(group.wastedBytes()).position(position).build());
		}

		List<SimilarityGroup> saved = saveInBatches(rows);

		List<SimilarityGroupMember> members = new ArrayList<>();

		for (int index = 0; index < saved.size(); index++) {
			members.addAll(membersOf(saved.get(index).getId(), groups.get(index).members()));
		}

		saveMembersInBatches(members);
	}

	private List<SimilarityGroupMember> membersOf(Long groupId, List<AnalyzedMember> analyzed) {
		List<SimilarityGroupMember> members = new ArrayList<>(analyzed.size());

		for (int position = 0; position < analyzed.size(); position++) {
			AnalyzedMember member = analyzed.get(position);

			members.add(SimilarityGroupMember.builder().groupId(groupId).mediaPublicId(member.mediaPublicId())
					.verdict(member.verdict()).reason(member.reason()).position(position).build());
		}

		return members;
	}

	private List<SimilarityGroup> saveInBatches(List<SimilarityGroup> rows) {
		List<SimilarityGroup> saved = new ArrayList<>(rows.size());

		for (int start = 0; start < rows.size(); start += BATCH_SIZE) {
			saved.addAll(groupRepository.saveAll(rows.subList(start, Math.min(start + BATCH_SIZE, rows.size()))));
		}

		return saved;
	}

	private void saveMembersInBatches(List<SimilarityGroupMember> members) {
		for (int start = 0; start < members.size(); start += BATCH_SIZE) {
			memberRepository.saveAll(members.subList(start, Math.min(start + BATCH_SIZE, members.size())));
		}
	}
}