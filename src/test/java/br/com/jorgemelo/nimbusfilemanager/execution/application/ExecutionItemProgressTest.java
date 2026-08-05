package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.ExecutionStepRepository;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * How far into the item being worked on right now - the half of the progress a
 * bar counting finished items cannot show, and the half that had to stop living
 * in memory when the work moved to another process.
 */
class ExecutionItemProgressTest {

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final AdvancingClock clock = new AdvancingClock();
	private final ExecutionOwnership ownership = Takings.owning(1L);

	@Test
	void recordsHowFarIntoTheCurrentItemTheWorkHasGot() {
		Execution execution = running();

		service().updateCurrentItem(ownership, 42);

		Assertions.assertThat(execution.getCurrentItemPercent()).isEqualTo(42);
	}

	/**
	 * ffmpeg speaks about once a second and would otherwise be a write a second,
	 * per encode, for a number nobody can read that fast.
	 */
	@Test
	void writesNothingWhileTheNumberHasNotChanged() {
		Execution execution = running();

		ExecutionProgressService service = service();

		service.updateCurrentItem(ownership, 42);

		execution.setCurrentItemPercent(null);

		service.updateCurrentItem(ownership, 42);

		Assertions.assertThat(execution.getCurrentItemPercent()).isNull();
	}

	/** A change arriving too soon waits for the next window rather than writing. */
	@Test
	void holdsBackAChangeThatArrivesWithinTheWindow() {
		Execution execution = running();

		ExecutionProgressService service = service();

		service.updateCurrentItem(ownership, 10);
		service.updateCurrentItem(ownership, 11);

		Assertions.assertThat(execution.getCurrentItemPercent()).isEqualTo(10);
	}

	@Test
	void writesAgainOnceTheWindowHasPassed() {
		Execution execution = running();

		ExecutionProgressService service = service();

		service.updateCurrentItem(ownership, 10);

		clock.advance(Duration.ofSeconds(2));

		service.updateCurrentItem(ownership, 11);

		Assertions.assertThat(execution.getCurrentItemPercent()).isEqualTo(11);
	}

	/** Anything ffmpeg says outside 0-100 is clamped rather than stored as read. */
	@Test
	void keepsThePercentageWithinItsOwnRange() {
		Execution execution = running();

		service().updateCurrentItem(ownership, 140);

		Assertions.assertThat(execution.getCurrentItemPercent()).isEqualTo(100);
	}

	/**
	 * Nothing is mid-item once the run has ended, and a row that kept its last
	 * percentage would show a bar frozen part-way for a job that finished.
	 */
	@Test
	void clearsWhatWasMidItemWhenTheExecutionEnds() {
		Execution execution = running();

		ExecutionProgressService service = service();

		service.updateCurrentItem(ownership, 42);
		service.fail(ownership, ExecutionMessages.executionInterrupted());

		Assertions.assertThat(execution.getCurrentItemPercent()).isNull();
	}

	/**
	 * And the throttle forgets it too, so a run that starts later is not measured
	 * against a window opened by a run that is over.
	 */
	@Test
	void startsTheWindowAfreshForARunThatComesLater() {
		Execution execution = running();

		ExecutionProgressService service = service();

		service.updateCurrentItem(ownership, 42);
		service.fail(ownership, ExecutionMessages.executionInterrupted());

		execution.setStatus(ExecutionStatus.RUNNING);

		service.updateCurrentItem(ownership, 42);

		Assertions.assertThat(execution.getCurrentItemPercent()).isEqualTo(42);
	}

	private Execution running() {
		Execution execution = Execution.builder().id(1L).status(ExecutionStatus.RUNNING).build();

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution));

		return execution;
	}

	/**
	 * The writer is the real one over the same repository: what these tests are
	 * about is when the write happens, and a mock in its place would answer that
	 * question by itself.
	 */
	private ExecutionProgressService service() {
		ExecutionStepRepository steps = mock(ExecutionStepRepository.class);

		when(steps.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		return new ExecutionProgressService(mock(ExecutionQueue.class), executionRepository,
				new ExecutionItemProgressWriter(executionRepository), steps,
				new ExecutionMessageCodec(new ObjectMapper()), clock);
	}
}