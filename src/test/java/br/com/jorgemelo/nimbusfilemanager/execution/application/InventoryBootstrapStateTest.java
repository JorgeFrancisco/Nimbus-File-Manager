package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Set;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * What counts as "the library has been catalogued once".
 *
 * <p>
 * The whole value of this class is which outcomes it accepts, so that is what
 * these pin. Widening the set by one constant - cancelled, say - would let the
 * geographic download start on top of a walk that covered half the library, and
 * nothing else in the suite would notice.
 */
class InventoryBootstrapStateTest {

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final InventoryBootstrapState inventoryBootstrapState = new InventoryBootstrapState(executionRepository);

	private Set<ExecutionStatus> statusesAsked() {
		ArgumentCaptor<Collection<ExecutionStatus>> statuses = ArgumentCaptor.captor();

		verify(executionRepository).existsByExecutionTypeAndStatusIn(eq(ExecutionType.INVENTORY),
				statuses.capture());

		return Set.copyOf(statuses.getValue());
	}

	/**
	 * A pass that walked the whole library counts, whether or not individual files
	 * failed on the way. Across this project the pair is one dichotomy: items
	 * failing is {@code FINISHED_WITH_ERRORS}, the run itself failing is
	 * {@code ERROR}.
	 */
	@Test
	void bothWaysOfWalkingTheWholeLibraryCount() {
		inventoryBootstrapState.hasCompletedAtLeastOnce();

		Assertions.assertThat(statusesAsked()).containsExactlyInAnyOrder(ExecutionStatus.FINISHED,
				ExecutionStatus.FINISHED_WITH_ERRORS);
	}

	/**
	 * The other five terminal outcomes, one at a time, so a future widening has to
	 * be deliberate. Cancelled and interrupted stopped part-way; error covers both
	 * a broken walk and one refused because another operation held the tree;
	 * rejected never started; and the two non-terminal ones are not outcomes.
	 */
	@ParameterizedTest
	@EnumSource(value = ExecutionStatus.class, names = { "PENDING", "RUNNING", "INTERRUPTED", "ERROR", "CANCELLED",
			"REJECTED" })
	void noOtherOutcomeMeansTheLibraryWasWalked(ExecutionStatus status) {
		inventoryBootstrapState.hasCompletedAtLeastOnce();

		Assertions.assertThat(statusesAsked()).doesNotContain(status);
	}

	/** It is about inventories, not about whatever else finished successfully. */
	@Test
	void asksAboutTheInventoryTypeAndNotAboutWhateverCompleted() {
		when(executionRepository.existsByExecutionTypeAndStatusIn(eq(ExecutionType.CONVERSION), any()))
				.thenReturn(true);

		Assertions.assertThat(inventoryBootstrapState.hasCompletedAtLeastOnce()).isFalse();
	}

	/**
	 * A library that legitimately holds nothing still counts as catalogued: the
	 * boundary is the run reaching the end, not what it found. Nothing here asks
	 * the catalog, which is what makes that true.
	 */
	@Test
	void aFinishedWalkCountsWhateverItFound() {
		when(executionRepository.existsByExecutionTypeAndStatusIn(eq(ExecutionType.INVENTORY), any()))
				.thenReturn(true);

		Assertions.assertThat(inventoryBootstrapState.hasCompletedAtLeastOnce()).isTrue();
	}
}