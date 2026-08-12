package br.com.jorgemelo.nimbusfilemanager.organization.domain.repository;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.PlanStatus;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.model.OrganizationPlanItemRecord;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.model.OrganizationPlanRecord;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import jakarta.persistence.EntityManager;

/**
 * The claims a mock cannot make: that a plan survives the process that built it,
 * that nothing partial is ever visible, that expiry is decided by stored state,
 * and that removing an execution takes its plan with it.
 *
 * <p>
 * All four are properties of the database - a conditional update, a filtered
 * query, a foreign key - so they are proved against a real PostgreSQL rather
 * than against a stub that would agree with whatever the code did.
 */
class OrganizationPlanRepositoryIntegrationTest extends SharedPostgresIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);

	@Autowired
	private OrganizationPlanRepository planRepository;

	@Autowired
	private OrganizationPlanItemRepository itemRepository;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private EntityManager entityManager;

	/**
	 * The plan is written by one process and read by another. Clearing the
	 * persistence context is what makes that literal here: every assertion after it
	 * comes from the database, not from an object somebody left in memory.
	 */
	@Test
	void aPublishedPlanIsReadableFromTheDatabaseAloneAndNotBeforeItIsPublished() {
		Long executionId = execution();

		planRepository.saveAndFlush(plan(executionId, PlanStatus.BUILDING, NOW.plusHours(12)));
		itemRepository.saveAllAndFlush(items(executionId, 3));

		entityManager.clear();

		// Still building: the screen sees nothing, even though every row is there.
		Assertions.assertThat(planRepository.findReadable(executionId, NOW)).isEmpty();
		Assertions.assertThat(itemRepository.countByExecutionId(executionId)).isEqualTo(3);

		Assertions.assertThat(planRepository.publish(executionId, 3, 1, 2, 4096L, "120:sig", NOW)).isEqualTo(1);

		entityManager.clear();

		OrganizationPlanRecord published = planRepository.findReadable(executionId, NOW).orElseThrow();

		Assertions.assertThat(published.getStatus()).isEqualTo(PlanStatus.READY);
		Assertions.assertThat(published.getItemCount()).isEqualTo(3);
		Assertions.assertThat(published.getConflictCount()).isEqualTo(1);
		Assertions.assertThat(published.getPlannedMoves()).isEqualTo(2);
		Assertions.assertThat(published.getCatalogSignature()).isEqualTo("120:sig");
		Assertions.assertThat(published.getBuiltAt()).isEqualTo(NOW);
	}

	@Test
	void onlyTheFirstOfTwoRacingPublicationsOfTheSamePlanWins() {
		Long executionId = execution();

		planRepository.saveAndFlush(plan(executionId, PlanStatus.BUILDING, NOW.plusHours(12)));

		Assertions.assertThat(planRepository.publish(executionId, 3, 0, 3, 1L, "first", NOW)).isEqualTo(1);
		Assertions.assertThat(planRepository.publish(executionId, 9, 9, 9, 9L, "second", NOW.plusMinutes(1)))
				.isZero();

		entityManager.clear();

		// The loser changed nothing: the plan somebody may already be reading is the
		// one that was published.
		OrganizationPlanRecord published = planRepository.findReadable(executionId, NOW).orElseThrow();

		Assertions.assertThat(published.getCatalogSignature()).isEqualTo("first");
		Assertions.assertThat(published.getItemCount()).isEqualTo(3);
	}

	/**
	 * Validity is a column, so it holds across restarts and means the same thing to
	 * both processes. Nothing has to remember to check a clock.
	 */
	@Test
	void aPlanPastItsExpiryStopsBeingReadableWithoutAnythingHavingRun() {
		Long executionId = execution();

		planRepository.saveAndFlush(plan(executionId, PlanStatus.READY, NOW.plusHours(1)));

		entityManager.clear();

		Assertions.assertThat(planRepository.findReadable(executionId, NOW)).isPresent();
		Assertions.assertThat(planRepository.findReadable(executionId, NOW.plusHours(2))).isEmpty();
	}

	@Test
	void aFailedPlanIsNeverReadable() {
		Long executionId = execution();

		planRepository.saveAndFlush(plan(executionId, PlanStatus.BUILDING, NOW.plusHours(12)));

		Assertions.assertThat(planRepository.markFailed(executionId)).isEqualTo(1);

		entityManager.clear();

		Assertions.assertThat(planRepository.findReadable(executionId, NOW)).isEmpty();

		// And a failed plan cannot be published afterwards: the update only moves rows
		// that are still building.
		Assertions.assertThat(planRepository.publish(executionId, 1, 0, 1, 1L, "sig", NOW)).isZero();
	}

	@Test
	void theSweepFindsPlansPastTheirExpiryWhateverStateTheyReached() {
		Long expiredReady = execution();
		Long expiredBuilding = execution();
		Long alive = execution();

		planRepository.saveAndFlush(plan(expiredReady, PlanStatus.READY, NOW.minusHours(1)));
		planRepository.saveAndFlush(plan(expiredBuilding, PlanStatus.BUILDING, NOW.minusHours(1)));
		planRepository.saveAndFlush(plan(alive, PlanStatus.READY, NOW.plusHours(12)));

		entityManager.clear();

		Assertions.assertThat(planRepository.findByExpiresAtBefore(NOW))
				.extracting(OrganizationPlanRecord::getExecutionId)
				.containsExactlyInAnyOrder(expiredReady, expiredBuilding);
	}

	/**
	 * Deleting the execution takes the plan and its items with it, by foreign key
	 * rather than by code somebody has to remember to write - which is why the
	 * retention sweep needs no knowledge of plans at all.
	 */
	@Test
	void removingTheExecutionRemovesItsPlanAndEveryItem() {
		Long executionId = execution();

		planRepository.saveAndFlush(plan(executionId, PlanStatus.READY, NOW.plusHours(12)));
		itemRepository.saveAllAndFlush(items(executionId, 5));

		executionRepository.deleteById(executionId);
		executionRepository.flush();

		entityManager.clear();

		Assertions.assertThat(planRepository.findById(executionId)).isEmpty();
		Assertions.assertThat(itemRepository.countByExecutionId(executionId)).isZero();
	}

	@Test
	void theConflictedItemsAreReadWithoutReadingTheOnesThatAreFine() {
		Long executionId = execution();

		planRepository.saveAndFlush(plan(executionId, PlanStatus.READY, NOW.plusHours(12)));
		itemRepository.saveAllAndFlush(items(executionId, 10));

		entityManager.clear();

		Assertions.assertThat(itemRepository.countByExecutionIdAndConflictTrue(executionId)).isEqualTo(5);
		Assertions.assertThat(itemRepository.findConflicts(executionId, PageRequest.of(0, 3)))
				.extracting(OrganizationPlanItemRecord::getOrdinal).containsExactly(0, 2, 4);
	}

	private Long execution() {
		return executionRepository.saveAndFlush(Execution.builder().executionType(ExecutionType.ORGANIZATION_PREVIEW)
				.status(ExecutionStatus.FINISHED).executionPublicId(UUID.randomUUID()).sourcePath("C:/input")
				.targetPath("C:/target").recursive(true).executeFlag(false).build()).getId();
	}

	private OrganizationPlanRecord plan(Long executionId, PlanStatus status, LocalDateTime expiresAt) {
		return OrganizationPlanRecord.builder().executionId(executionId).sourcePath("C:/input")
				.targetPath("C:/target").layout(OrganizationLayout.DEFAULT).status(status).itemCount(0)
				.conflictCount(0).plannedMoves(0).totalSizeBytes(0L).expiresAt(expiresAt).build();
	}

	private List<OrganizationPlanItemRecord> items(Long executionId, int count) {
		return IntStream.range(0, count)
				.mapToObj(ordinal -> OrganizationPlanItemRecord.builder().executionId(executionId).ordinal(ordinal)
						.catalogFileId(UUID.randomUUID()).fileName("file" + ordinal + ".jpg")
						.sourcePath("C:/input/file" + ordinal + ".jpg")
						.targetPath("C:/target/file" + ordinal + ".jpg").sizeBytes(100L)
						.conflict(ordinal % 2 == 0).build())
				.toList();
	}
}