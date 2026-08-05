package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityComposition;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFamily;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Reason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGrouping;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityGroupingRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * The collector for analyses that were written down and never became the
 * answer.
 *
 * <p>
 * Every property here is about which rows survive a pass, so a mock could not
 * show any of it: what is being asserted is the state of three tables, and the
 * cascade that empties two of them is the database's, not the mapping's. The one
 * exception is the last test, where the property is that a failure is contained
 * - for that the repository has to fail on demand, which only a stub can do.
 *
 * <p>
 * The clock is fixed and the rows are stamped against it, so "a day old" is a
 * fact of the fixture rather than of when the suite happens to run.
 */
@SpringBootTest
@Testcontainers
class SimilarityGroupingSweeperIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private SimilarityGroupingRepository groupingRepository;

	@Autowired
	private SimilarityPublisher publisher;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

	private SimilarityGroupingSweeper sweeper;

	@BeforeEach
	void arm() {
		sweeper = new SimilarityGroupingSweeper(groupingRepository, clock);
	}

	@AfterEach
	void disarm() {
		sweeper.shutdown();

		groupingRepository.deleteAll();
		executionRepository.deleteAll();
	}

	/** The case the sweeper exists for: built, never promoted, and a day old. */
	@Test
	void aGroupingBuiltAndNeverPublishedIsRemovedOnceItIsADayOld() {
		SimilarityGrouping abandoned = built("abandoned", NOW.minusHours(25));

		sweeper.runOnce();

		assertThat(groupingRepository.findById(abandoned.getId())).isEmpty();
	}

	/**
	 * And it takes its groups and members with it. The cascade is declared in the
	 * schema rather than in the mapping, so deleting the header is the whole
	 * operation - which is only true if the database says so.
	 */
	@Test
	void theGroupsAndMembersOfARemovedGroupingGoWithIt() {
		SimilarityGrouping abandoned = built("cascade", NOW.minusHours(25));

		assertThat(groupsOf(abandoned)).isEqualTo(2);
		assertThat(membersOf(abandoned)).isEqualTo(4);

		sweeper.runOnce();

		assertThat(groupsOf(abandoned)).isZero();
		assertThat(membersOf(abandoned)).isZero();
	}

	/**
	 * A grouping written an hour ago is left alone. It cannot in fact still be
	 * under construction - the whole result is one transaction, so a visible row is
	 * a finished one - but the window is what makes that reasoning unnecessary.
	 */
	@Test
	void aGroupingWrittenRecentlyIsLeftAlone() {
		SimilarityGrouping recent = built("recent", NOW.minusHours(1));

		sweeper.runOnce();

		assertThat(groupingRepository.findById(recent.getId())).isPresent();
	}

	/** The answer the screen is showing is never this sweeper's business. */
	@Test
	void theAnswerOnScreenIsNeverTouchedHoweverOldItIs() {
		SimilarityGrouping answer = stored("active", GroupingStatus.ACTIVE, NOW.minusDays(30));

		sweeper.runOnce();

		assertThat(groupingRepository.findById(answer.getId())).get()
				.extracting(SimilarityGrouping::getStatus).isEqualTo(GroupingStatus.ACTIVE);
	}

	/**
	 * Nor is a retired one. A superseded analysis is history and has a retention of
	 * its own; deleting it here would be this sweeper deciding somebody else's
	 * policy.
	 */
	@Test
	void aSupersededAnalysisIsNotThisSweepersBusiness() {
		SimilarityGrouping retired = stored("superseded", GroupingStatus.SUPERSEDED, NOW.minusDays(30));

		sweeper.runOnce();

		assertThat(groupingRepository.findById(retired.getId())).get()
				.extracting(SimilarityGrouping::getStatus).isEqualTo(GroupingStatus.SUPERSEDED);
	}

	/**
	 * Running twice changes nothing the first pass did not already change. It runs
	 * every hour for the life of the process, so "no residue" has to be a resting
	 * state rather than a one-off outcome.
	 */
	@Test
	void aSecondPassOverTheSameStateRemovesNothingMore() {
		SimilarityGrouping abandoned = built("idempotent-abandoned", NOW.minusHours(25));
		SimilarityGrouping recent = built("idempotent-recent", NOW.minusHours(1));
		SimilarityGrouping answer = stored("idempotent-active", GroupingStatus.ACTIVE, NOW.minusDays(30));

		sweeper.runOnce();

		List<Long> afterFirst = surviving();

		sweeper.runOnce();

		assertThat(surviving()).isEqualTo(afterFirst);
		assertThat(afterFirst).containsExactlyInAnyOrder(recent.getId(), answer.getId())
				.doesNotContain(abandoned.getId());
	}

	/**
	 * A pass that fails must not take the timer with it: an executor cancels a
	 * repeating task the moment one run lets an exception escape, and a sweep that
	 * stops forever is a much worse outcome than residue waiting another hour.
	 */
	@Test
	void aFailedPassDoesNotKillTheOnesAfterIt() {
		SimilarityGroupingRepository failing = mock(SimilarityGroupingRepository.class);

		when(failing.findByStatusAndComputedAtBefore(any(), any()))
				.thenThrow(new DataAccessResourceFailureException("the database went away")).thenReturn(List.of());

		SimilarityGroupingSweeper unlucky = new SimilarityGroupingSweeper(failing, clock);

		try {
			assertThatCode(unlucky::runOnce).as("the failing pass").doesNotThrowAnyException();
			assertThatCode(unlucky::runOnce).as("the pass after it").doesNotThrowAnyException();

			verify(failing, times(2)).findByStatusAndComputedAtBefore(any(), any());
		} finally {
			unlucky.shutdown();
		}
	}

	private List<Long> surviving() {
		return groupingRepository.findAll().stream().map(SimilarityGrouping::getId).sorted().toList();
	}

	private int groupsOf(SimilarityGrouping grouping) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM similarity_group WHERE grouping_id = ?",
				Integer.class, grouping.getId());
	}

	private int membersOf(SimilarityGrouping grouping) {
		return jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM similarity_group_member m
				JOIN similarity_group g ON g.id = m.group_id
				WHERE g.grouping_id = ?
				""", Integer.class, grouping.getId());
	}

	/**
	 * A real BUILDING result, written by the real publisher so the groups and
	 * members underneath it are real too, and then stamped with the age the test
	 * needs - the publisher uses the application's clock, not this one.
	 */
	private SimilarityGrouping built(String digest, LocalDateTime computedAt) {
		SimilarityGrouping grouping = publisher.build(result(digest), ranBy());

		jdbcTemplate.update("UPDATE similarity_grouping SET computed_at = ? WHERE id = ?",
				Timestamp.valueOf(computedAt), grouping.getId());

		return grouping;
	}

	/**
	 * A grouping in a terminal state, old enough that a sweeper filtering by age
	 * alone would take it. Its own parameters digest, because the partial unique
	 * index allows one ACTIVE per family.
	 */
	private SimilarityGrouping stored(String digest, GroupingStatus status, LocalDateTime computedAt) {
		return groupingRepository.saveAndFlush(SimilarityGrouping.builder().publicId(UUID.randomUUID())
				.mediaType(FileType.PHOTO).algorithmId(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1)
				.groupingVersion(SimilarityConstants.GROUPING_VERSION).parametersDigest(digest(digest))
				.compositionDigest(digest("composition")).eligibleCount(120).analyzedCount(120).candidateLimit(8000)
				.selectionPolicy(SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST).status(status)
				.computedAt(computedAt).publishedAt(computedAt).groupCount(2).memberCount(4).build());
	}

	private SimilarityAnalysisResult result(String digest) {
		return new SimilarityAnalysisResult(
				new SimilarityFamily(FileType.PHOTO, FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1,
						SimilarityConstants.GROUPING_VERSION, digest(digest)),
				new SimilarityComposition(digest("composition"), 120, 120, 8000,
						SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST),
				List.of(group(), group()));
	}

	private AnalyzedGroup group() {
		return new AnalyzedGroup(96, 2048L,
				List.of(new AnalyzedMember(UUID.randomUUID(), Verdict.KEEP, Reason.ORIGINAL),
						new AnalyzedMember(UUID.randomUUID(), Verdict.DELETE_CANDIDATE, Reason.DERIVATIVE)));
	}

	/** The column is 64 characters; the readable part is what identifies the row. */
	private String digest(String name) {
		return name + "-".repeat(64 - name.length());
	}

	private Long ranBy() {
		return executionRepository.saveAndFlush(Execution.builder().executionType(ExecutionType.SIMILARITY_PHOTO)
				.status(ExecutionStatus.RUNNING).recursive(false).executeFlag(true).build()).getId();
	}
}