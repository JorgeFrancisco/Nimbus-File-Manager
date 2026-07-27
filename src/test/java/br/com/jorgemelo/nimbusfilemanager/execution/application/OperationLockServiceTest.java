package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;

class OperationLockServiceTest {

	private final OperationLockService operationLockService = new OperationLockService();

	@Test
	void acquireShouldAllowNestedLockInSameThreadAndReleaseItAfterClose() {
		Path parent = Path.of("C:/media");
		Path child = Path.of("C:/media/2024");

		try (var _ = operationLockService.acquire(ExecutionType.INVENTORY, parent)) {
			try (var nested = operationLockService.acquire(ExecutionType.ORGANIZATION, child)) {
				Assertions.assertThat(nested).isNotNull();
			}
		}

		try (var ignored = operationLockService.acquire(ExecutionType.ORGANIZATION, child)) {
			Assertions.assertThat(ignored).isNotNull();
		}
	}

	@Test
	void acquireShouldAllowOnlyOneConcurrentExecutionForSamePath() throws Exception {
		CountDownLatch firstLockAcquired = new CountDownLatch(1);
		CountDownLatch releaseFirstLock = new CountDownLatch(1);

		AtomicReference<Throwable> firstThreadFailure = new AtomicReference<>();
		AtomicReference<Throwable> secondThreadFailure = new AtomicReference<>();

		Thread firstThread = new Thread(() -> {
			try (var _ = operationLockService.acquire(ExecutionType.INVENTORY, Path.of("C:/media"))) {
				firstLockAcquired.countDown();
				releaseFirstLock.await();
			} catch (Throwable e) {
				firstThreadFailure.set(e);
			}
		});

		Thread secondThread = new Thread(() -> {
			try {
				firstLockAcquired.await(2, TimeUnit.SECONDS);
				operationLockService.acquire(ExecutionType.ORGANIZATION, Path.of("C:/media"));
			} catch (Throwable e) {
				secondThreadFailure.set(e);
			}
		});

		firstThread.start();
		secondThread.start();
		secondThread.join();
		releaseFirstLock.countDown();
		firstThread.join();

		Assertions.assertThat(firstThreadFailure.get()).isNull();
		Assertions.assertThat(secondThreadFailure.get()).isInstanceOf(OperationLockException.class);
	}

	@Test
	void isBusyShouldReportConflictingLockHeldByAnotherThreadWithoutThrowing() throws Exception {
		CountDownLatch acquired = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		Thread holder = new Thread(() -> {
			try (var _ = operationLockService.acquire(ExecutionType.ORGANIZATION, Path.of("C:/media"))) {
				acquired.countDown();
				release.await(2, TimeUnit.SECONDS);
			} catch (Exception _) {
				// interrupted; nothing to do
			}
		});

		holder.start();

		Assertions.assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue();

		// Containment-aware: a lock on C:/media makes the whole subtree busy.
		Assertions.assertThat(operationLockService.isBusy(Path.of("C:/media/2024"))).isTrue();
		Assertions.assertThat(operationLockService.isBusy(Path.of("C:/other"))).isFalse();

		release.countDown();
		holder.join();

		Assertions.assertThat(operationLockService.isBusy(Path.of("C:/media"))).isFalse();
	}

	/**
	 * A library often sits on a whole drive, and a drive root is the one path whose
	 * normalised form already ends in a separator. Building the containment prefix
	 * by appending another one produced something no path could start with, so a
	 * lock on a file did not conflict with a request for the drive holding it: a
	 * conversion could run while the watcher started an inventory over the same
	 * tree. The temporary directory gives a real root on both operating systems -
	 * a literal would be a relative path on the Linux build.
	 */
	@Test
	void isBusyShouldSeeThroughADriveRootInEitherDirection(@TempDir Path folder) throws Exception {
		Path root = folder.getRoot();

		whileLockedOnAnotherThread(folder, () -> Assertions.assertThat(operationLockService.isBusy(root)).isTrue());

		whileLockedOnAnotherThread(root, () -> Assertions.assertThat(operationLockService.isBusy(folder)).isTrue());
	}

	/**
	 * A batch the user started has nobody to retry it, so it waits for background
	 * maintenance to finish instead of refusing the click. Here the holder releases
	 * while the waiter is already waiting, which is the case that used to fail.
	 */
	@Test
	void acquireWithinWaitsForTheHolderToReleaseAndThenTakesTheLock(@TempDir Path tmp) throws Exception {
		Path locked = tmp.resolve("library");

		AtomicReference<OperationLock> taken = new AtomicReference<>();

		Thread waiter = new Thread(() -> {
			try (var lock = operationLockService.acquireWithin(Duration.ofSeconds(10), ExecutionType.CONVERSION,
					locked)) {
				taken.set(lock);
			}
		});

		whileLockedOnAnotherThread(locked, () -> {
			waiter.start();

			awaitWaitingOnTheLock(waiter);
		});

		waiter.join();

		Assertions.assertThat(taken.get()).isNotNull();
	}

	/** The wait is bounded: a holder that never lets go still gets a refusal. */
	@Test
	void acquireWithinGivesUpOnceTheTimeoutPasses(@TempDir Path tmp) throws Exception {
		Path locked = tmp.resolve("library");
		Duration shortWait = Duration.ofMillis(120);

		whileLockedOnAnotherThread(locked,
				() -> Assertions
						.assertThatThrownBy(
								() -> operationLockService.acquireWithin(shortWait, ExecutionType.CONVERSION, locked))
						.isInstanceOf(OperationLockException.class).hasMessageContaining("already running"));
	}

	/**
	 * A shutdown interrupts whoever is waiting. The wait has to end as a refusal
	 * with the interrupt flag preserved, never as a thread stuck until its own
	 * deadline.
	 */
	@Test
	void acquireWithinStopsWaitingWhenTheThreadIsInterrupted(@TempDir Path tmp) throws Exception {
		Path locked = tmp.resolve("library");

		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicReference<Boolean> interrupted = new AtomicReference<>();

		CountDownLatch acquired = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		// This holder keeps the lock until the test says otherwise. The shared helper
		// lets go after two seconds, which on a loaded CI runner arrived before the
		// waiter had even been scheduled: it then took the lock legitimately and the
		// test called that a failure.
		Thread holder = new Thread(() -> {
			try (var _ = operationLockService.acquire(ExecutionType.INVENTORY, locked)) {
				acquired.countDown();
				release.await(30, TimeUnit.SECONDS);
			} catch (InterruptedException _) {
				Thread.currentThread().interrupt();
			}
		});

		Thread waiter = new Thread(() -> {
			try (var _ = operationLockService.acquireWithin(Duration.ofMinutes(5), ExecutionType.CONVERSION, locked)) {
				failure.set(new AssertionError("the lock should never have been granted"));
			} catch (OperationLockException _) {
				interrupted.set(Thread.currentThread().isInterrupted());
			}
		});

		holder.start();

		Assertions.assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();

		waiter.start();

		Assertions.assertThat(awaitWaitingOnTheLock(waiter)).isTrue();

		waiter.interrupt();
		waiter.join();

		release.countDown();
		holder.join();

		Assertions.assertThat(failure.get()).isNull();
		Assertions.assertThat(interrupted.get()).isTrue();
	}

	/**
	 * Waits until the thread is parked inside the timed wait, so the release (or
	 * the interrupt) that follows lands while it is genuinely waiting.
	 *
	 * <p>
	 * It yields rather than spinning: a busy spin starves the very thread it is
	 * waiting for when the runner has few cores, which is how this passed on a
	 * developer machine and failed in CI. Bounded so a wait that never happens
	 * fails the test instead of hanging the build.
	 */
	private static boolean awaitWaitingOnTheLock(Thread waiter) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

		while (waiter.getState() != Thread.State.TIMED_WAITING) {
			if (System.nanoTime() > deadline) {
				return false;
			}

			Thread.yield();
		}

		return true;
	}

	/** A free path is granted immediately, with no wait at all. */
	@Test
	void acquireWithinDoesNotWaitWhenNothingConflicts(@TempDir Path tmp) {
		try (var lock = operationLockService.acquireWithin(Duration.ofMinutes(5), ExecutionType.CONVERSION,
				tmp.resolve("library"))) {
			Assertions.assertThat(lock).isNotNull();
		}
	}

	/**
	 * Runs {@code assertions} while another thread holds a lock on {@code locked},
	 * because both {@code acquire} and {@code isBusy} ignore the thread that owns
	 * the lock and would report no conflict from inside the test thread.
	 */
	private void whileLockedOnAnotherThread(Path locked, Runnable assertions) throws Exception {
		CountDownLatch acquired = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		Thread holder = new Thread(() -> {
			try (var _ = operationLockService.acquire(ExecutionType.CONVERSION, locked)) {
				acquired.countDown();
				release.await(2, TimeUnit.SECONDS);
			} catch (Exception _) {
				// interrupted; nothing to do
			}
		});

		holder.start();

		Assertions.assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue();

		assertions.run();

		release.countDown();
		holder.join();
	}
}