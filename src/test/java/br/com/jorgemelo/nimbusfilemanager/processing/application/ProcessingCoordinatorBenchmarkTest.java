package br.com.jorgemelo.nimbusfilemanager.processing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.processing.application.dto.Outcome;
import br.com.jorgemelo.nimbusfilemanager.processing.application.dto.Result;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;

/**
 * Benchmark comparing the current sequential extraction against the new
 * coordinator with 1, 2, 3 and 4 workers. Each item simulates a fixed-cost
 * extraction (a sleep), so wall-clock differences reflect parallelism rather
 * than real ffmpeg variance.
 *
 * <p>
 * The 1-worker run isolates the overhead the new infrastructure adds without
 * the benefit of concurrency. The report prints <b>accumulated</b> time (sum of
 * per-item durations) next to the real <b>wall-clock</b> elapsed, because under
 * parallelism the accumulated sum is expected to exceed the wall-clock and must
 * never be read as a direct reduction of total time.
 */
class ProcessingCoordinatorBenchmarkTest {

	private static final int ITEMS = 48;
	private static final long PER_ITEM_MILLIS = 20;

	@Test
	void benchmarkSequentialVersusOneTwoThreeAndFourWorkers() {
		List<Integer> items = IntStream.range(0, ITEMS).boxed().toList();

		long sequentialWall = timeSequential(items);

		System.out.printf("%n[processing-benchmark] items=%d, per-item=%dms%n", ITEMS, PER_ITEM_MILLIS);

		System.out.printf("[processing-benchmark] sequential            wall=%5dms  accumulated=%5dms%n",
				ms(sequentialWall), (long) ITEMS * PER_ITEM_MILLIS);

		for (int workers = 1; workers <= 4; workers++) {
			Result result = timeParallel(items, workers);

			System.out.printf(
					"[processing-benchmark] coordinator workers=%d  wall=%5dms  accumulated=%5dms  maxConcurrency=%d%n",
					workers, ms(result.wallNanos()), ms(result.accumulatedTaskNanos()), result.maxConcurrency());

			assertThat(result.outcomes()).hasSize(ITEMS);
			assertThat(result.outcomes()).allMatch(Outcome::executed);

			for (int i = 0; i < ITEMS; i++) {
				assertThat(result.outcomes().get(i).item()).isEqualTo(i);
			}

			// What the coordinator actually promises, and what a bug would break: it
			// runs items at the same time, and never more of them than its pool allows.
			// The numbers printed above are the benchmark; asserting on them would be a
			// bet on how busy the machine is, which is what this used to do - the whole
			// suite runs in parallel, and a 4-worker run starved by the other classes
			// once took longer than the sequential baseline and failed a green build.
			// Concurrency survives that, because the items sleep rather than compute.
			assertThat(result.maxConcurrency()).isBetween(1, workers);

			if (workers > 1) {
				assertThat(result.maxConcurrency()).isGreaterThan(1);
			}
		}
	}

	private long timeSequential(List<Integer> items) {
		long start = System.nanoTime();

		for (Integer item : items) {
			sleep();
			assertThat(item).isNotNull();
		}

		return System.nanoTime() - start;
	}

	private Result timeParallel(List<Integer> items, int workers) {
		ProcessingMetrics metrics = new ProcessingMetrics();

		ProcessingCoordinator coordinator = new ProcessingCoordinator(
				new ProcessingProperties(workers, ITEMS, 2, 2, 2, 1), metrics);

		try {
			long start = System.nanoTime();

			List<Outcome<Integer, Integer>> outcomes = coordinator.process(items, () -> false, item -> {
				sleep();

				return item;
			});

			long wall = System.nanoTime() - start;

			return new Result(outcomes, wall, metrics.snapshot().taskTotalNanos(), metrics.snapshot().maxConcurrency());
		} finally {
			coordinator.shutdown();
		}
	}

	private void sleep() {
		try {
			Thread.sleep(PER_ITEM_MILLIS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();

			throw new IllegalStateException(e);
		}
	}

	private long ms(long nanos) {
		return TimeUnit.NANOSECONDS.toMillis(nanos);
	}
}