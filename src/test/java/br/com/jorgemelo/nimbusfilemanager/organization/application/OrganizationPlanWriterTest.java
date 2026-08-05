package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationItem;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPlan;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationSummary;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.PlanStatus;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.model.OrganizationPlanItemRecord;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.model.OrganizationPlanRecord;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.OrganizationPlanItemRepository;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.OrganizationPlanRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.OrganizationPlanProperties;

/**
 * Writing a plan is split in two, and the split is the whole protocol: a long
 * transaction writes rows nothing can see, and a short one makes them the
 * answer. These tests hold that - what is written is BUILDING, and only the
 * second step publishes.
 */
class OrganizationPlanWriterTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);

	private final OrganizationPlanRepository planRepository = mock(OrganizationPlanRepository.class);
	private final OrganizationPlanItemRepository itemRepository = mock(OrganizationPlanItemRepository.class);
	private final CatalogSignature catalogSignature = mock(CatalogSignature.class);
	private final Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

	private final OrganizationPlanWriter writer = new OrganizationPlanWriter(planRepository, itemRepository,
			catalogSignature, new OrganizationPlanProperties(null), clock);

	@Test
	void whatIsWrittenIsInvisibleUntilItIsPublished() {
		ArgumentCaptor<OrganizationPlanRecord> saved = ArgumentCaptor.forClass(OrganizationPlanRecord.class);

		writer.build(42L, plan(items(2)));

		verify(planRepository).save(saved.capture());

		Assertions.assertThat(saved.getValue().getStatus()).isEqualTo(PlanStatus.BUILDING);
		Assertions.assertThat(saved.getValue().getExecutionId()).isEqualTo(42L);
		Assertions.assertThat(saved.getValue().getSourcePath()).isEqualTo("C:/input");

		// The counts are the publication's business: a building plan that claimed them
		// would be a plan describing rows that may not all be there yet.
		Assertions.assertThat(saved.getValue().getItemCount()).isZero();
		Assertions.assertThat(saved.getValue().getConflictCount()).isZero();

		verify(planRepository, never()).publish(anyLong(), anyInt(), anyInt(), anyInt(), anyLong(), anyString(),
				any());
	}

	/**
	 * The expiry is stamped when the plan is born rather than when it publishes, so
	 * a run that dies halfway leaves a row the ordinary sweep collects instead of
	 * one that never expires.
	 */
	@Test
	void aPlanExpiresOnItsOwnScheduleFromTheMomentItIsOpened() {
		ArgumentCaptor<OrganizationPlanRecord> saved = ArgumentCaptor.forClass(OrganizationPlanRecord.class);

		writer.build(42L, plan(items(1)));

		verify(planRepository).save(saved.capture());

		Assertions.assertThat(saved.getValue().getExpiresAt())
				.isEqualTo(NOW.plusHours(OrganizationPlanProperties.DEFAULT_TTL_HOURS));
	}

	@Test
	void aConfiguredExpiryIsHonouredAndAnImpossibleOneIsNot() {
		Assertions.assertThat(new OrganizationPlanProperties(3).ttlHoursOrDefault()).isEqualTo(3);
		Assertions.assertThat(new OrganizationPlanProperties(0).ttlHoursOrDefault())
				.isEqualTo(OrganizationPlanProperties.DEFAULT_TTL_HOURS);
		Assertions.assertThat(new OrganizationPlanProperties(100_000).ttlHoursOrDefault())
				.isEqualTo(OrganizationPlanProperties.DEFAULT_TTL_HOURS);
	}

	@Test
	void everyItemKeepsTheOrdinalThePlannerGaveIt() {
		ArgumentCaptor<List<OrganizationPlanItemRecord>> rows = ArgumentCaptor.captor();

		writer.build(42L, plan(items(3)));

		verify(itemRepository).saveAll(rows.capture());

		Assertions.assertThat(rows.getValue()).extracting(OrganizationPlanItemRecord::getOrdinal).containsExactly(0, 1,
				2);
		Assertions.assertThat(rows.getValue()).extracting(OrganizationPlanItemRecord::getFileName)
				.containsExactly("file0.jpg", "file1.jpg", "file2.jpg");
		Assertions.assertThat(rows.getValue().getFirst().getExecutionId()).isEqualTo(42L);
	}

	@Test
	void aPlanLargerThanOneBatchIsWrittenInSeveralRoundTrips() {
		writer.build(42L, plan(items(501)));

		verify(itemRepository, times(2)).saveAll(any());
	}

	@Test
	void publishingRecordsTheCountsAndTheCatalogItWasBuiltOver() {
		when(catalogSignature.of("C:/input")).thenReturn("120:2026-05-01T09:00");
		when(planRepository.publish(eq(42L), anyInt(), anyInt(), anyInt(), anyLong(), anyString(), any()))
				.thenReturn(1);

		Assertions.assertThat(writer.publish(42L, plan(items(3)))).isTrue();

		verify(planRepository).publish(42L, 3, 1, 2, 4096L, "120:2026-05-01T09:00", NOW);
	}

	/**
	 * A plan that is no longer BUILDING was already decided by somebody else, and
	 * the caller is told so rather than assuming it won the race.
	 */
	@Test
	void aPlanThatIsNoLongerBuildingIsNotPublished() {
		when(planRepository.publish(anyLong(), anyInt(), anyInt(), anyInt(), anyLong(), any(), any())).thenReturn(0);

		Assertions.assertThat(writer.publish(42L, plan(items(1)))).isFalse();
	}

	@Test
	void aRunThatDiesLeavesThePlanMarkedFailed() {
		writer.markFailed(42L);

		verify(planRepository).markFailed(42L);
	}

	private OrganizationPlan plan(List<OrganizationItem> items) {
		long conflicts = items.stream().filter(OrganizationItem::conflict).count();
		long moves = items.stream().filter(item -> !item.samePath()).count();

		return new OrganizationPlan("C:/input", "C:/target", OrganizationLayout.DEFAULT, false,
				new OrganizationSummary(items.size(), 0, 0, 0, moves, 4096L, conflicts, 0, 0), items);
	}

	private List<OrganizationItem> items(int count) {
		List<OrganizationItem> items = new ArrayList<>();

		for (int index = 0; index < count; index++) {
			// One conflicted item, and one that is already where it belongs, so the
			// published counts are not all the same number.
			boolean conflict = index == 1;
			boolean samePath = index == 2;

			items.add(new OrganizationItem(null, UUID.randomUUID(), "file" + index + ".jpg",
					"C:/input/file" + index + ".jpg", "C:/target/file" + index + ".jpg", null, null, null, null, null,
					null, null, 100L, samePath, false, false, false, conflict, conflict ? "TARGET_EXISTS" : null,
					null, null));
		}

		return items;
	}
}