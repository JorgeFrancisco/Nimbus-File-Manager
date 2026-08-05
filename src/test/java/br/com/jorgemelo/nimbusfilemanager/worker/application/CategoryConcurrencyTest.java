package br.com.jorgemelo.nimbusfilemanager.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;

/**
 * The third limit, and the one nothing else can express.
 *
 * <p>
 * The loops bound how many executions run at all; the processing pool bounds the
 * files in flight inside one; the tool gate bounds ffmpeg processes. None of
 * them can say "one conversion at a time", and the requests a person makes are
 * deliberately not deduplicated - two conversions of two different videos are
 * two different jobs, on different paths, with no dedup key between them. What
 * stops them from being simultaneous is only this.
 */
class CategoryConcurrencyTest {

	@Test
	void letsThroughAsManyOfATypeAsThatTypeAllows() {
		CategoryConcurrency concurrency = with(handler(ExecutionType.INVENTORY, 2));

		assertThat(concurrency.tryEnter(ExecutionType.INVENTORY)).isTrue();
		assertThat(concurrency.tryEnter(ExecutionType.INVENTORY)).isTrue();
	}

	@Test
	void refusesASecondExecutionOfATypeLimitedToOne() {
		CategoryConcurrency concurrency = with(handler(ExecutionType.CONVERSION, 1));

		assertThat(concurrency.tryEnter(ExecutionType.CONVERSION)).isTrue();
		assertThat(concurrency.tryEnter(ExecutionType.CONVERSION)).isFalse();
	}

	/**
	 * A saturated type must not stop the rest. A worker whose conversions are all
	 * busy goes on claiming inventories - which is the difference between a limit
	 * and a queue that stopped.
	 */
	@Test
	void doesNotLetOneFullTypeHoldUpTheOthers() {
		CategoryConcurrency concurrency = with(handler(ExecutionType.CONVERSION, 1),
				handler(ExecutionType.INVENTORY, 2));

		concurrency.tryEnter(ExecutionType.CONVERSION);

		assertThat(concurrency.tryEnter(ExecutionType.INVENTORY)).isTrue();
		assertThat(concurrency.typesWithCapacity()).containsExactly(ExecutionType.INVENTORY.name());
	}

	/**
	 * A full type is left out of the question put to the queue, so a worker never
	 * claims a row it is not allowed to start.
	 */
	@Test
	void stopsAskingTheQueueAboutATypeItIsAlreadyRunning() {
		CategoryConcurrency concurrency = with(handler(ExecutionType.CONVERSION, 1));

		assertThat(concurrency.typesWithCapacity()).containsExactly(ExecutionType.CONVERSION.name());

		concurrency.tryEnter(ExecutionType.CONVERSION);

		assertThat(concurrency.typesWithCapacity()).isEmpty();
	}

	@Test
	void givesTheSlotBackWhenTheExecutionEnds() {
		CategoryConcurrency concurrency = with(handler(ExecutionType.CONVERSION, 1));

		concurrency.tryEnter(ExecutionType.CONVERSION);
		concurrency.leave(ExecutionType.CONVERSION);

		assertThat(concurrency.tryEnter(ExecutionType.CONVERSION)).isTrue();
	}

	/** A type nothing here can run has no slot, and no claim to a slot. */
	@Test
	void refusesATypeItHasNoHandlerFor() {
		CategoryConcurrency concurrency = with(handler(ExecutionType.INVENTORY, 1));

		assertThat(concurrency.tryEnter(ExecutionType.UNDO)).isFalse();
	}

	private CategoryConcurrency with(ExecutionJobHandler... handlers) {
		return new CategoryConcurrency(List.of(handlers));
	}

	private ExecutionJobHandler handler(ExecutionType type, int limit) {
		ExecutionJobHandler handler = JobHandlerMock.answeringItsOwnDefaults();

		when(handler.type()).thenReturn(type);
		when(handler.concurrencyLimit()).thenReturn(limit);

		return handler;
	}
}