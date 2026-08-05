package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.StoredPlanPage;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.PlanStatus;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.model.OrganizationPlanItemRecord;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.model.OrganizationPlanRecord;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.OrganizationPlanItemRepository;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.OrganizationPlanRepository;

/**
 * Reading a published plan.
 *
 * <p>
 * The two things worth pinning are what it refuses to show and what it warns
 * about. A plan that is missing, building, failed or expired all read as
 * nothing, because they are the same answer to a screen; and a plan built over a
 * catalog that has since moved is still shown, with the fact that it moved.
 */
class OrganizationPlanReaderTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);

	private final OrganizationPlanRepository planRepository = mock(OrganizationPlanRepository.class);
	private final OrganizationPlanItemRepository itemRepository = mock(OrganizationPlanItemRepository.class);
	private final CatalogSignature catalogSignature = mock(CatalogSignature.class);
	private final Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

	private final OrganizationPlanReader reader = new OrganizationPlanReader(planRepository, itemRepository,
			catalogSignature, clock);

	@Test
	void aPublishedPlanIsReadOnePageAtATime() {
		when(planRepository.findReadable(42L, NOW)).thenReturn(Optional.of(plan("sig", 120, 4)));
		when(itemRepository.findByExecutionIdOrderByOrdinalAsc(42L, PageRequest.of(0, 50))).thenReturn(rows(3));
		when(catalogSignature.of("C:/input")).thenReturn("sig");

		StoredPlanPage page = reader.page(42L, 0, 50, false).orElseThrow();

		Assertions.assertThat(page.sourcePath()).isEqualTo("C:/input");
		Assertions.assertThat(page.targetPath()).isEqualTo("C:/target");
		Assertions.assertThat(page.layout()).isEqualTo(OrganizationLayout.DEFAULT);
		Assertions.assertThat(page.totalItems()).isEqualTo(120);
		Assertions.assertThat(page.items()).hasSize(3);
		Assertions.assertThat(page.summary().plannedMoves()).isEqualTo(100);
		Assertions.assertThat(page.summary().conflicts()).isEqualTo(4);
	}

	/**
	 * The query the reader has to ask is what makes "only conflicts" survive a plan
	 * of a hundred thousand items: the conflicted rows are a different read, not a
	 * filter over rows this process would otherwise have to hold.
	 */
	@Test
	void onlyConflictsIsADifferentReadAndCountsAgainstTheConflictTotal() {
		when(planRepository.findReadable(42L, NOW)).thenReturn(Optional.of(plan("sig", 120, 4)));
		when(itemRepository.findConflicts(42L, PageRequest.of(0, 50))).thenReturn(rows(4));

		StoredPlanPage page = reader.page(42L, 0, 50, true).orElseThrow();

		Assertions.assertThat(page.totalItems()).isEqualTo(4);
		Assertions.assertThat(page.items()).hasSize(4);

		verify(itemRepository, never()).findByExecutionIdOrderByOrdinalAsc(anyLong(), any());
	}

	/**
	 * Expiry is a column, so it is the query that refuses - the reader never has to
	 * remember to check a clock, and the same answer holds after any restart.
	 */
	@Test
	void aPlanThatIsNotReadableIsSimplyAbsent() {
		when(planRepository.findReadable(42L, NOW)).thenReturn(Optional.empty());

		Assertions.assertThat(reader.page(42L, 0, 50, false)).isEmpty();

		verify(itemRepository, never()).findByExecutionIdOrderByOrdinalAsc(anyLong(), any());
		verify(itemRepository, never()).findConflicts(anyLong(), any());
	}

	@Test
	void aPlanBuiltOverACatalogThatHasSinceMovedSaysSo() {
		when(planRepository.findReadable(42L, NOW)).thenReturn(Optional.of(plan("120:yesterday", 120, 0)));
		when(itemRepository.findByExecutionIdOrderByOrdinalAsc(any(), any())).thenReturn(rows(1));
		when(catalogSignature.of("C:/input")).thenReturn("121:today");

		Assertions.assertThat(reader.page(42L, 0, 50, false).orElseThrow().catalogChanged()).isTrue();
	}

	/**
	 * A plan with no signature is not evidence that anything moved. Warning about
	 * every one of them would teach the user to ignore the warning.
	 */
	@Test
	void aPlanWithoutASignatureDoesNotClaimTheCatalogMoved() {
		when(planRepository.findReadable(42L, NOW)).thenReturn(Optional.of(plan(null, 1, 0)));
		when(itemRepository.findByExecutionIdOrderByOrdinalAsc(any(), any())).thenReturn(rows(1));

		Assertions.assertThat(reader.page(42L, 0, 50, false).orElseThrow().catalogChanged()).isFalse();

		verify(catalogSignature, never()).of(any());
	}

	@Test
	void aPageBeyondTheLastOneFallsBackToTheLastPage() {
		when(planRepository.findReadable(42L, NOW)).thenReturn(Optional.of(plan("sig", 120, 0)));
		when(itemRepository.findByExecutionIdOrderByOrdinalAsc(42L, PageRequest.of(2, 50))).thenReturn(rows(20));

		Assertions.assertThat(reader.page(42L, 99, 50, false).orElseThrow().page()).isEqualTo(2);
	}

	@Test
	void anEmptyPlanStillAnswersWithItsFirstPage() {
		when(planRepository.findReadable(42L, NOW)).thenReturn(Optional.of(plan("sig", 0, 0)));
		when(itemRepository.findByExecutionIdOrderByOrdinalAsc(42L, PageRequest.of(0, 50))).thenReturn(List.of());

		StoredPlanPage page = reader.page(42L, 0, 50, false).orElseThrow();

		Assertions.assertThat(page.items()).isEmpty();
		Assertions.assertThat(page.totalItems()).isZero();
	}

	private OrganizationPlanRecord plan(String signature, int itemCount, int conflictCount) {
		return OrganizationPlanRecord.builder().executionId(42L).sourcePath("C:/input").targetPath("C:/target")
				.layout(OrganizationLayout.DEFAULT).status(PlanStatus.READY).itemCount(itemCount)
				.conflictCount(conflictCount).plannedMoves(100).totalSizeBytes(4096L).catalogSignature(signature)
				.builtAt(NOW.minusHours(1)).expiresAt(NOW.plusHours(11)).build();
	}

	private List<OrganizationPlanItemRecord> rows(int count) {
		List<OrganizationPlanItemRecord> rows = new ArrayList<>();

		for (int ordinal = 0; ordinal < count; ordinal++) {
			rows.add(OrganizationPlanItemRecord.builder().executionId(42L).ordinal(ordinal)
					.catalogFileId(UUID.randomUUID()).fileName("file" + ordinal + ".jpg")
					.sourcePath("C:/input/file" + ordinal + ".jpg").targetPath("C:/target/file" + ordinal + ".jpg")
					.sizeBytes(100L).conflict(false).build());
		}

		return rows;
	}
}