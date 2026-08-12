package br.com.jorgemelo.nimbusfilemanager.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.processing.application.ExternalToolGate;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingCoordinator;
import br.com.jorgemelo.nimbusfilemanager.processing.application.dto.Outcome;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhaseType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExternalToolCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.PhaseSnapshot;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.Snapshot;

/**
 * What a shared accumulator could not tell apart.
 *
 * <p>
 * These cross the two places where executions really do meet: one
 * {@link ExternalToolGate}, whose permits are deliberately shared, and one
 * {@link ProcessingCoordinator}, whose pool is deliberately shared. Sharing
 * those is right - a second inventory must not double the ffmpeg processes on
 * the machine. What was wrong was sharing the <em>counting</em>: both used to
 * write into one Spring-managed {@code ProcessingMetrics}, so every assertion
 * below would have read the same number for both sides, and the whole file
 * would pass while measuring nothing.
 *
 * <p>
 * That is what makes each of these discriminating: they run concurrently
 * through one gate and one pool, and then require the two contexts to disagree.
 * Under the old singleton the two snapshots were literally the same object's
 * sum, so no arrangement of numbers could make them differ.
 */
class ExecutionMetricsIsolationTest {

	private static final int WAIT_SECONDS = 5;

	/** One permit per category, so a second caller has to wait and be counted. */
	private static final ProcessingProperties ONE_AT_A_TIME = new ProcessingProperties(4, 16, 1, 1, 1, 1);

	/**
	 * A: two kinds of work at once, through the same gate. A photo run and a video
	 * run overlap deliberately, and each has to come back holding only its own
	 * category. Shared, both snapshots reported both categories.
	 */
	@Test
	void aPhotoRunAndAVideoRunThroughOneGateCountOnlyTheirOwnCategory() throws Exception {
		ExternalToolGate gate = new ExternalToolGate(ONE_AT_A_TIME);

		ExecutionMetricsContext photoRun = new ExecutionMetricsContext();
		ExecutionMetricsContext videoRun = new ExecutionMetricsContext();

		CountDownLatch bothInside = new CountDownLatch(2);

		Thread photo = gated(gate, ExternalToolCategory.FFMPEG_PHOTO_HASH, photoRun, bothInside);
		Thread video = gated(gate, ExternalToolCategory.FFPROBE_VIDEO, videoRun, bothInside);

		join(photo, video);

		Snapshot photoSnapshot = photoRun.processing().snapshot();
		Snapshot videoSnapshot = videoRun.processing().snapshot();

		assertThat(photoSnapshot.categories().get(ExternalToolCategory.FFMPEG_PHOTO_HASH).runs()).isEqualTo(1);
		assertThat(photoSnapshot.categories().get(ExternalToolCategory.FFPROBE_VIDEO).runs()).isZero();

		assertThat(videoSnapshot.categories().get(ExternalToolCategory.FFPROBE_VIDEO).runs()).isEqualTo(1);
		assertThat(videoSnapshot.categories().get(ExternalToolCategory.FFMPEG_PHOTO_HASH).runs()).isZero();
	}

	/**
	 * B: the harder half of A - same category, same permit, so the two runs
	 * genuinely contend. One waits for the other and pays a gate wait; the
	 * accumulated wait belongs to whoever waited, not to both.
	 */
	@Test
	void twoPhotoRunsContendingForOnePermitEachKeepTheirOwnWait() throws Exception {
		ExternalToolGate gate = new ExternalToolGate(ONE_AT_A_TIME);

		ExecutionMetricsContext first = new ExecutionMetricsContext();
		ExecutionMetricsContext second = new ExecutionMetricsContext();

		CountDownLatch firstIsInside = new CountDownLatch(1);
		CountDownLatch firstMayLeave = new CountDownLatch(1);

		Thread holder = new Thread(() -> run(() -> gate.run(ExternalToolCategory.FFMPEG_PHOTO_HASH, first.processing(),
				() -> {
					firstIsInside.countDown();

					return firstMayLeave.await(WAIT_SECONDS, TimeUnit.SECONDS);
				})));

		holder.start();

		assertThat(firstIsInside.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

		Thread waiter = new Thread(() -> run(() -> gate.run(ExternalToolCategory.FFMPEG_PHOTO_HASH,
				second.processing(), () -> null)));

		waiter.start();

		// Parked on the semaphore, not merely started. Sleeping instead would be a
		// guess about scheduling, and under a loaded suite the guess loses: the
		// permit gets released before the waiter ever reaches the gate, and then
		// nobody waited for anything.
		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> waiter.getState() == Thread.State.WAITING);

		firstMayLeave.countDown();

		join(holder, waiter);

		long heldWait = first.processing().snapshot().categories().get(ExternalToolCategory.FFMPEG_PHOTO_HASH)
				.gateWaitNanos();
		long queuedWait = second.processing().snapshot().categories().get(ExternalToolCategory.FFMPEG_PHOTO_HASH)
				.gateWaitNanos();

		// Relative, not absolute: how long the wait lasted is the machine's business,
		// but whose accumulator it landed in is this test's.
		assertThat(queuedWait).as("the one that queued paid the wait").isGreaterThan(heldWait);
	}

	/**
	 * C: the worker pool. Two runs of different sizes drive the same coordinator at
	 * the same time; each must come back with its own count. Shared, both read the
	 * sum, which is exactly the reading that made a fingerprint run look like it
	 * had processed the inventory's files too.
	 */
	@Test
	void twoRunsSharingOnePoolEachCountOnlyTheirOwnTasks() throws Exception {
		ProcessingCoordinator coordinator = new ProcessingCoordinator(ONE_AT_A_TIME);

		ExecutionMetricsContext smallRun = new ExecutionMetricsContext();
		ExecutionMetricsContext largeRun = new ExecutionMetricsContext();

		CountDownLatch bothStarted = new CountDownLatch(2);

		Thread small = processing(coordinator, 3, smallRun, bothStarted);
		Thread large = processing(coordinator, 7, largeRun, bothStarted);

		join(small, large);

		assertThat(smallRun.processing().snapshot().tasksExecuted()).isEqualTo(3);
		assertThat(largeRun.processing().snapshot().tasksExecuted()).isEqualTo(7);
	}

	/**
	 * D: phase timings, which used to be the other shared singleton. Two runs
	 * measure the same phase with different amounts, and neither ends up holding
	 * the other's milliseconds.
	 */
	@Test
	void twoRunsMeasuringTheSamePhaseDoNotAddUpIntoOneAnother() {
		ExecutionMetricsContext first = new ExecutionMetricsContext();
		ExecutionMetricsContext second = new ExecutionMetricsContext();

		first.phases().addNanos(ExecutionPhaseType.EXTRACTION, TimeUnit.MILLISECONDS.toNanos(1));
		first.phases().addItems(ExecutionPhaseType.EXTRACTION, 1);

		second.phases().addNanos(ExecutionPhaseType.EXTRACTION, TimeUnit.MILLISECONDS.toNanos(9));
		second.phases().addItems(ExecutionPhaseType.EXTRACTION, 9);

		PhaseSnapshot firstPhase = first.phases().snapshot().get(ExecutionPhaseType.EXTRACTION);
		PhaseSnapshot secondPhase = second.phases().snapshot().get(ExecutionPhaseType.EXTRACTION);

		assertThat(firstPhase.durationMillis()).isEqualTo(1);
		assertThat(firstPhase.items()).isEqualTo(1);

		assertThat(secondPhase.durationMillis()).isEqualTo(9);
		assertThat(secondPhase.items()).isEqualTo(9);
	}

	/**
	 * E: how a run ends is its own business. One cancels and one errors while both
	 * are counting; neither outcome shows up in the other, and neither clears
	 * anything - the clearing that used to happen at the start of a run is what
	 * made a concurrent execution lose what it had measured.
	 */
	@Test
	void oneRunCancellingOrFailingLeavesWhatAnotherHasCountedAlone() {
		ExecutionMetricsContext cancelled = new ExecutionMetricsContext();
		ExecutionMetricsContext failed = new ExecutionMetricsContext();
		ExecutionMetricsContext succeeded = new ExecutionMetricsContext();

		succeeded.processing().incExecuted();
		succeeded.processing().incExecuted();

		cancelled.processing().incCancelled();

		failed.processing().incError();

		assertThat(succeeded.processing().snapshot().tasksExecuted()).isEqualTo(2);
		assertThat(succeeded.processing().snapshot().tasksCancelled()).isZero();
		assertThat(succeeded.processing().snapshot().tasksError()).isZero();

		assertThat(cancelled.processing().snapshot().tasksExecuted()).isZero();
		assertThat(failed.processing().snapshot().tasksExecuted()).isZero();
	}

	/**
	 * F: a run that starts while another is mid-flight begins at zero, and nothing
	 * had to be cleared for that to be true. There is no clearing to call any more:
	 * being new <em>is</em> being empty.
	 */
	@Test
	void aRunStartingWhileAnotherIsUnderWayBeginsEmptyWithoutClearingIt() {
		ExecutionMetricsContext underWay = new ExecutionMetricsContext();

		underWay.processing().incExecuted();
		underWay.processing().recordExternalExec(ExternalToolCategory.FFMPEG_PHOTO_HASH, 1_000);
		underWay.phases().addNanos(ExecutionPhaseType.PERSISTENCE, TimeUnit.MILLISECONDS.toNanos(2));

		ExecutionMetricsContext starting = new ExecutionMetricsContext();

		Snapshot fresh = starting.processing().snapshot();

		assertThat(fresh.tasksExecuted()).isZero();
		assertThat(fresh.categories().get(ExternalToolCategory.FFMPEG_PHOTO_HASH).externalExecNanos()).isZero();
		assertThat(starting.phases().snapshot()).isEmpty();

		assertThat(underWay.processing().snapshot().tasksExecuted()).as("the running one lost nothing").isEqualTo(1);
		assertThat(underWay.phases().snapshot()).isNotEmpty();
	}

	/**
	 * Enters the gate and stays inside until the other one is in too, so the
	 * overlap is a fact of the test rather than a hope about timing.
	 */
	private Thread gated(ExternalToolGate gate, ExternalToolCategory category, ExecutionMetricsContext context,
			CountDownLatch bothInside) {
		Thread thread = new Thread(() -> run(() -> gate.run(category, context.processing(), () -> {
			bothInside.countDown();

			return bothInside.await(WAIT_SECONDS, TimeUnit.SECONDS);
		})));

		thread.start();

		return thread;
	}

	private Thread processing(ProcessingCoordinator coordinator, int items, ExecutionMetricsContext context,
			CountDownLatch bothStarted) {
		List<Integer> work = IntStream.range(0, items).boxed().toList();

		Thread thread = new Thread(() -> {
			bothStarted.countDown();

			List<Outcome<Integer, Integer>> outcomes = coordinator.process(work, () -> false, item -> {
				bothStarted.await(WAIT_SECONDS, TimeUnit.SECONDS);

				return item;
			}, context.processing());

			assertThat(outcomes).hasSize(items);
		});

		thread.start();

		return thread;
	}

	private void run(Callable<?> call) {
		try {
			call.call();
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private void join(Thread... threads) throws InterruptedException {
		for (Thread thread : threads) {
			thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

			assertThat(thread.isAlive()).as("thread finished within the timeout").isFalse();
		}
	}
}