package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityComposition;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFamily;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGrouping;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityGroupingRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityGroupingWriter;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
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

	private final SimilarityGroupingRepository groupingRepository;
	private final SimilarityGroupingWriter groupingWriter;
	private final Clock clock;

	SimilarityPublisher(SimilarityGroupingRepository groupingRepository, SimilarityGroupingWriter groupingWriter,
			Clock clock) {
		this.groupingRepository = groupingRepository;
		this.groupingWriter = groupingWriter;
		this.clock = clock;
	}

	/**
	 * Writes the analysis as BUILDING and returns it. Nothing reads a grouping in
	 * this state, so the insertion can take as long as it takes - though it no
	 * longer takes long.
	 *
	 * <p>
	 * The header goes through JPA because it is one row and the entity is what the
	 * publication then works with; the groups and members go through
	 * {@link SimilarityGroupingWriter}, because they are tens of thousands and JPA
	 * spent ninety per cent of its time on round trips it could not batch. Both are
	 * in this transaction, so a half-written result is still impossible.
	 *
	 * <p>
	 * {@code saveAndFlush} rather than {@code save}: the writer's rows point at
	 * this one by foreign key, and it is going through JDBC rather than through the
	 * persistence context, so the header has to be in the database and not merely
	 * in the session.
	 */
	@Transactional
	SimilarityGrouping build(SimilarityAnalysisResult result, Long executionId) {
		SimilarityFamily family = result.family();

		SimilarityComposition composition = result.composition();

		SimilarityGrouping grouping = groupingRepository.saveAndFlush(SimilarityGrouping.builder()
				.mediaType(family.mediaType()).algorithmId(family.algorithmId())
				.groupingVersion(family.groupingVersion()).parametersDigest(family.parametersDigest())
				.compositionDigest(composition.digest()).eligibleCount(composition.eligibleCount())
				.analyzedCount(composition.analyzedCount()).candidateLimit(composition.candidateLimit())
				.selectionPolicy(composition.selectionPolicy()).status(GroupingStatus.BUILDING)
				.computedAt(LocalDateTime.now(clock)).executionId(executionId)
				.groupCount(result.groups().size()).memberCount(result.memberCount()).build());

		groupingWriter.write(grouping.getId(), result.groups());

		return grouping;
	}

	/**
	 * The id of the family's current answer, or {@code null} when it has none.
	 *
	 * <p>
	 * Read before an incremental run starts, so that what it produces can be
	 * refused if the answer it was derived from stops being the answer. A digest
	 * would not do: two analyses of the same family have the same parameters by
	 * definition, so only the row identifies which one a result was built on top
	 * of.
	 */
	@Transactional(readOnly = true)
	Long currentAnswer(SimilarityFamily family) {
		return groupingRepository
				.findActive(family.mediaType(), family.algorithmId(), family.groupingVersion(),
						family.parametersDigest())
				.map(SimilarityGrouping::getId).orElse(null);
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
	 * <p>
	 * Whatever is current is retired, without asking which analysis it was: a
	 * rebuild owes nothing to the answer it replaces, having recomputed every
	 * relation from the fingerprints. A result <em>derived</em> from the current
	 * answer is a different case and goes through {@link #publishIfStillBasedOn}.
	 *
	 * <p>
	 * The taking is pinned first and held for both statements. Retiring what is
	 * current and promoting this one are one decision, and an analysis that ran for
	 * minutes is exactly the thing that can finish after the row it was running for
	 * was given to somebody else: without this, the older answer would retire the
	 * newer one and take its place, with nothing on either row to show which was
	 * which.
	 *
	 * @return whether this grouping became the answer
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	boolean publish(SimilarityGrouping grouping, ExecutionOwnership ownership) {
		if (!supersededByItsOwnTaking(grouping, ownership)) {
			return false;
		}

		int retired = groupingRepository.supersedeActive(grouping.getMediaType(), grouping.getAlgorithmId(),
				grouping.getGroupingVersion(), grouping.getParametersDigest());

		return promote(grouping, retired);
	}

	/**
	 * Promotes an incremental result, but only if the world it was derived from is
	 * still the world.
	 *
	 * <p>
	 * A regroup reaches its answer by reading relations rather than by recomputing
	 * them, and it starts from the analysis that is current when it starts. If a
	 * rebuild publishes while it works, promoting afterwards would replace a newer
	 * and more complete answer with conclusions drawn about an older one - and
	 * nothing about the row would show it, because both carry the same parameters.
	 *
	 * <p>
	 * Checked by naming the row rather than by holding a lock across the run: the
	 * conditional update is the check, so an analysis that takes a minute costs the
	 * family nothing until the instant it publishes. What loses is discarded, not
	 * retried - the screen has an answer, and it is the better of the two.
	 *
	 * @param basedOnActiveId the answer that was current when the run started, or
	 * {@code null} when the family had none - in which case it must still have none
	 * @return whether this grouping became the answer
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	boolean publishIfStillBasedOn(SimilarityGrouping grouping, Long basedOnActiveId, ExecutionOwnership ownership) {
		if (!supersededByItsOwnTaking(grouping, ownership)) {
			return false;
		}

		if (basedOnActiveId == null) {
			return promoteIfStillUnanswered(grouping);
		}

		int retired = groupingRepository.supersede(basedOnActiveId);

		if (retired == 0) {
			log.info("Similarity grouping {} was derived from an answer that is no longer current, so it was not"
					+ " published; the analysis that replaced it stands", grouping.getSimilarityGroupingPublicId());

			return false;
		}

		return promote(grouping, retired);
	}

	/**
	 * Whether the run offering this answer is still the taking of its execution,
	 * held so for the rest of this transaction.
	 *
	 * <p>
	 * The conditional updates below are about which <em>analysis</em> a result was
	 * derived from, which is a different question and does not answer this one: two
	 * takings of the same execution produce results derived from the same current
	 * answer, so both would pass that check. This is what tells them apart.
	 */
	private boolean supersededByItsOwnTaking(SimilarityGrouping grouping, ExecutionOwnership ownership) {
		if (ownership.pin()) {
			return true;
		}

		log.info("Similarity grouping {} was not published: the execution that produced it is no longer the current"
				+ " taking of its row, and the answer that replaced it stands",
				grouping.getSimilarityGroupingPublicId());

		return false;
	}

	private boolean promoteIfStillUnanswered(SimilarityGrouping grouping) {
		if (groupingRepository.findActive(grouping.getMediaType(), grouping.getAlgorithmId(),
				grouping.getGroupingVersion(), grouping.getParametersDigest()).isPresent()) {
			log.info("Similarity grouping {} was derived from a family that had no answer, and one was published"
					+ " meanwhile, so it was not published", grouping.getSimilarityGroupingPublicId());

			return false;
		}

		return promote(grouping, 0);
	}

	private boolean promote(SimilarityGrouping grouping, int retired) {
		int published = groupingRepository.publish(grouping.getId(), LocalDateTime.now(clock));

		if (published == 0) {
			// Not BUILDING any more: something else already decided this grouping's
			// fate - a reclaim cleaned it up, or it was published twice.
			log.warn("Similarity grouping {} was no longer building and was not published",
					grouping.getSimilarityGroupingPublicId());

			return false;
		}

		log.info("Published similarity grouping {} for {} ({} groups, {} of {} files analysed), retiring {} previous",
				grouping.getSimilarityGroupingPublicId(), grouping.getMediaType(), grouping.getGroupCount(),
				grouping.getAnalyzedCount(), grouping.getEligibleCount(), retired);

		return true;
	}

}