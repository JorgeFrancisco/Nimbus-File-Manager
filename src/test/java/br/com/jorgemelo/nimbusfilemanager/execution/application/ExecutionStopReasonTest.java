package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * The one question every writer asks before touching the next file, and the two
 * answers that are not failures of the work.
 */
class ExecutionStopReasonTest {

	private final ExecutionCancellationService executionCancellationService = mock(
			ExecutionCancellationService.class);
	private final ExecutionStopReason stopReason = new ExecutionStopReason(executionCancellationService);

	@Test
	void answersNothingWhileThereIsNoReasonToStop() {
		Assertions.assertThat(stopReason.of(execution(), owning())).isNull();
	}

	/** A person asked, which is a cancel and never an error. */
	@Test
	void answersCancelledWhenSomebodyAskedItToStop() {
		when(executionCancellationService.isCancelled(7L)).thenReturn(true);

		ExecutionOwnership ownership = owning();

		Assertions.assertThat(stopReason.of(execution(), ownership)).isEqualTo(ExecutionStatus.CANCELLED);

		// The cancel settles it: there is no point asking about locks the run is not
		// going to use.
		verify(ownership, never()).assertMayGoOnWorking();
	}

	/**
	 * The locks under the run went away. Nothing about the work went wrong, and
	 * the row must not say it did.
	 */
	@Test
	void answersInterruptedWhenThePathsAreNoLongerOwned() {
		ExecutionOwnership lost = mock(ExecutionOwnership.class);

		doThrow(new OwnershipLostException("the lease went away")).when(lost).assertMayGoOnWorking();

		Assertions.assertThat(stopReason.of(execution(), lost)).isEqualTo(ExecutionStatus.INTERRUPTED);
	}

	private Execution execution() {
		return Execution.builder().id(7L).executionType(ExecutionType.QUARANTINE_PURGE)
				.status(ExecutionStatus.RUNNING).build();
	}

	private ExecutionOwnership owning() {
		return mock(ExecutionOwnership.class);
	}
}