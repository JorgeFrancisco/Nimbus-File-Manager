package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.PlanStatus;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.model.OrganizationPlanRecord;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.OrganizationPlanRepository;

/**
 * The sweep that keeps a plan from outliving its usefulness.
 *
 * <p>
 * What it must never do is touch an execution: "a preview was asked for" is
 * history and keeps the executions screen's retention, while the plan is an
 * artifact somebody looked at and goes much sooner. The sweeper only knows about
 * plans, which is what makes that impossible rather than merely intended.
 */
class OrganizationPlanSweeperTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);

	private final OrganizationPlanRepository planRepository = mock(OrganizationPlanRepository.class);
	private final Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

	private final OrganizationPlanSweeper sweeper = new OrganizationPlanSweeper(planRepository, clock);

	@Test
	void everyPlanPastItsExpiryIsRemoved() {
		List<OrganizationPlanRecord> expired = List.of(plan(1L, PlanStatus.READY), plan(2L, PlanStatus.BUILDING));

		when(planRepository.findByExpiresAtBefore(NOW)).thenReturn(expired);

		sweeper.runOnce();

		ArgumentCaptor<Iterable<OrganizationPlanRecord>> deleted = ArgumentCaptor.captor();

		verify(planRepository).deleteAll(deleted.capture());

		Assertions.assertThat(deleted.getValue()).containsExactlyElementsOf(expired);
	}

	@Test
	void aPassWithNothingToRemoveDeletesNothing() {
		when(planRepository.findByExpiresAtBefore(NOW)).thenReturn(List.of());

		sweeper.runOnce();

		verify(planRepository, never()).deleteAll(any());
	}

	/**
	 * A scheduled pass that lets an exception escape kills its own timer for the
	 * lifetime of the process. An expired plan lingering until the next hour is a
	 * much smaller problem than never sweeping again.
	 */
	@Test
	void aFailedPassDoesNotKillTheTimer() {
		when(planRepository.findByExpiresAtBefore(NOW)).thenReturn(List.of(plan(1L, PlanStatus.READY)));

		doThrow(new IllegalStateException("the database went away")).when(planRepository).deleteAll(any());

		Assertions.assertThatCode(sweeper::runOnce).doesNotThrowAnyException();
	}

	private OrganizationPlanRecord plan(Long executionId, PlanStatus status) {
		return OrganizationPlanRecord.builder().executionId(executionId).sourcePath("C:/input")
				.targetPath("C:/target").layout(OrganizationLayout.DEFAULT).status(status).itemCount(0)
				.conflictCount(0).plannedMoves(0).totalSizeBytes(0L).expiresAt(NOW.minusHours(1)).build();
	}
}