package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Waiting for an execution without owning it.
 *
 * <p>
 * The budget running out is not an outcome, and the two tests that matter say
 * exactly that: what it answers when the work finished in time, and that it
 * simply stops asking when it did not - having claimed nothing, cancelled
 * nothing and left the row exactly as it found it.
 */
class ExecutionCompletionWaitTest {

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);

	private final ExecutionCompletionWait wait = new ExecutionCompletionWait(executionRepository);

	@Test
	void answersWithTheExecutionOnceItHasFinished() {
		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution(ExecutionStatus.FINISHED)));

		assertThat(wait.awaitTerminal(1L, Duration.ofMillis(200))).isPresent();
	}

	/**
	 * Still running when the budget ran out. The answer is empty - which says
	 * nothing about the execution except that it had not finished yet - and the row
	 * was only ever read.
	 */
	@Test
	void stopsWaitingWithoutTouchingTheExecution() {
		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution(ExecutionStatus.RUNNING)));

		assertThat(wait.awaitTerminal(1L, Duration.ofMillis(120))).isEmpty();

		verify(executionRepository, atLeast(2)).findById(1L);
		verify(executionRepository, never()).save(any());
	}

	/**
	 * Interrupted mid-wait: it stops, and leaves the flag set for whoever asked it
	 * to stop. Swallowing the interruption and going round again would be a request
	 * thread that ignores a shutdown.
	 */
	@Test
	void stopsWaitingWhenTheThreadIsInterrupted() {
		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution(ExecutionStatus.RUNNING)));

		Thread.currentThread().interrupt();

		try {
			assertThat(wait.awaitTerminal(1L, Duration.ofSeconds(30))).isEmpty();
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		} finally {
			Thread.interrupted();
		}
	}

	/**
	 * A row that is not there cannot be waited for, and pretending otherwise would
	 * spend the whole budget on an execution nobody can find.
	 */
	@Test
	void givesUpAtOnceOnAnExecutionThatIsNotThere() {
		when(executionRepository.findById(1L)).thenReturn(Optional.empty());

		assertThat(wait.awaitTerminal(1L, Duration.ofSeconds(5))).isEmpty();

		verify(executionRepository).findById(1L);
	}

	private Execution execution(ExecutionStatus status) {
		return Execution.builder().id(1L).executionType(ExecutionType.EXPLORER_RENAME).status(status).build();
	}
}