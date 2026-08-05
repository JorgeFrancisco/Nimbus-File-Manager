package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;

/**
 * Mutual exclusion, against the database that now provides it.
 *
 * <p>
 * This replaced a unit test of a map and a monitor. There is nothing left to
 * unit test: the question "do these two operations conflict?" is answered by
 * PostgreSQL comparing advisory keys, so the only honest way to ask it is with
 * real sessions. Each acquire here borrows its own connection, which is what
 * two operations - in one process or two - actually do.
 */
@SpringBootTest
@Testcontainers
class OperationLockServiceIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private OperationLockService operationLockService;

	@Test
	void grantsAFreePath(@TempDir Path folder) {
		try (OperationLock lock = operationLockService.acquire(ExecutionType.INVENTORY, folder)) {
			assertThat(lock.executionType()).isEqualTo(ExecutionType.INVENTORY);
			assertThat(lock.displayPath()).isEqualTo(folder.toString());
		}
	}

	@Test
	void refusesAPathAnotherOperationAlreadyHolds(@TempDir Path folder) throws Exception {
		try (var _ = holdElsewhere(ExecutionType.INVENTORY, folder)) {
			assertThatExceptionOfType(OperationLockException.class)
					.isThrownBy(() -> operationLockService.acquire(ExecutionType.ORGANIZATION, folder))
					.withMessageContaining(folder.toString());
		}
	}

	@Test
	void grantsThePathAgainOnceTheHolderCloses(@TempDir Path folder) {
		operationLockService.acquire(ExecutionType.INVENTORY, folder).close();

		assertThatCode(() -> operationLockService.acquire(ExecutionType.ORGANIZATION, folder).close())
				.doesNotThrowAnyException();
	}

	/**
	 * The hierarchy, which is the whole reason the key chain includes ancestors: a
	 * rename inside a folder has to collide with an inventory of the folder, even
	 * though the two paths are not equal.
	 */
	@Test
	void refusesAChildPathWhileAnAncestorIsHeld(@TempDir Path folder) throws Exception {
		Path child = folder.resolve("2008").resolve("a.jpg");

		try (var _ = holdElsewhere(ExecutionType.INVENTORY, folder)) {
			assertThatExceptionOfType(OperationLockException.class)
					.isThrownBy(() -> operationLockService.acquire(ExecutionType.ORGANIZATION, child));
		}
	}

	@Test
	void refusesAnAncestorWhileAChildIsHeld(@TempDir Path folder) throws Exception {
		Path child = folder.resolve("2008");

		try (var _ = holdElsewhere(ExecutionType.ORGANIZATION, child)) {
			assertThatExceptionOfType(OperationLockException.class)
					.isThrownBy(() -> operationLockService.acquire(ExecutionType.INVENTORY, folder));
		}
	}

	@Test
	void grantsTwoPathsThatDoNotContainEachOther(@TempDir Path folder) throws Exception {
		try (var _ = holdElsewhere(ExecutionType.INVENTORY, folder.resolve("fotos"))) {
			assertThatCode(() -> operationLockService.acquire(ExecutionType.INVENTORY, folder.resolve("videos"))
					.close()).doesNotThrowAnyException();
		}
	}

	/**
	 * A move takes both ends. Whichever end is busy, the whole operation is
	 * refused - and nothing is left half-held, or the refused caller would still
	 * be blocking others.
	 */
	@Test
	void refusesAMoveWhenEitherEndIsBusy(@TempDir Path folder) throws Exception {
		Path source = folder.resolve("origem");
		Path target = folder.resolve("destino");

		try (var _ = holdElsewhere(ExecutionType.INVENTORY, target)) {
			assertThatExceptionOfType(OperationLockException.class)
					.isThrownBy(() -> operationLockService.acquire(ExecutionType.ORGANIZATION, source, target));
		}

		assertThatCode(() -> operationLockService.acquire(ExecutionType.INVENTORY, source).close())
				.doesNotThrowAnyException();
	}

	@Test
	void waitsForTheHolderAndThenGrants(@TempDir Path folder) throws Exception {
		AutoCloseable held = holdElsewhere(ExecutionType.INVENTORY, folder);

		CompletableFuture<Void> waiting = CompletableFuture.runAsync(
				() -> operationLockService.acquireWithin(Duration.ofSeconds(20), ExecutionType.ORGANIZATION, folder)
						.close());

		held.close();

		assertThatCode(waiting::get).doesNotThrowAnyException();
	}

	@Test
	void refusesOnceTheWaitIsExhausted(@TempDir Path folder) throws Exception {
		Duration shortWait = Duration.ofMillis(300);

		try (var _ = holdElsewhere(ExecutionType.INVENTORY, folder)) {
			assertThatExceptionOfType(OperationLockException.class)
					.isThrownBy(() -> acquireWaiting(shortWait, folder));
		}
	}

	@Test
	void reportsABusyTreeWithoutTakingAnything(@TempDir Path folder) throws Exception {
		assertThat(operationLockService.isBusy(folder)).isFalse();

		try (var _ = holdElsewhere(ExecutionType.INVENTORY, folder)) {
			assertThat(operationLockService.isBusy(folder)).isTrue();
			assertThat(operationLockService.isBusy(folder.resolve("2008"))).isTrue();
		}

		assertThat(operationLockService.isBusy(folder)).isFalse();
	}

	/**
	 * The check a long operation makes before every mutation. While the session
	 * holds its keys the answer is yes; once the lock is closed - or, in
	 * production, once the server restarted and dropped every advisory lock - it
	 * must be no, because that is what stops files being moved without exclusion.
	 */
	@Test
	void confirmsOwnershipWhileHeldAndDeniesItAfterwards(@TempDir Path folder) {
		OperationLock lock = operationLockService.acquire(ExecutionType.INVENTORY, folder);

		assertThat(operationLockService.stillHolds(lock)).isTrue();

		lock.close();

		assertThat(operationLockService.stillHolds(lock)).isFalse();
	}

	// The conflict matrix, spelled out. Each case names two operations and the
	// answer the product needs, because "hierarchical locking" can mean either the
	// design that works or the one that turns a whole volume into a mutex, and
	// only these cases tell them apart.

	/** A: two files in one folder - independent work, must coexist. */
	@Test
	void allowsTwoOperationsOnSiblingFilesOfTheSameFolder(@TempDir Path folder) throws Exception {
		Path first = folder.resolve("a.jpg");
		Path second = folder.resolve("b.jpg");

		try (var _ = holdElsewhere(ExecutionType.ORGANIZATION, first)) {
			assertThatCode(() -> operationLockService.acquire(ExecutionType.ORGANIZATION, second).close())
					.doesNotThrowAnyException();
		}
	}

	/** C: two independent trees under one root - must coexist. */
	@Test
	void allowsOperationsOnTwoIndependentTrees(@TempDir Path folder) throws Exception {
		Path photos = folder.resolve("Fotos");
		Path videos = folder.resolve("Videos");

		try (var _ = holdElsewhere(ExecutionType.INVENTORY, photos)) {
			assertThatCode(() -> operationLockService.acquire(ExecutionType.CONVERSION, videos).close())
					.doesNotThrowAnyException();
		}
	}

	/** D: two years under one library - must coexist. */
	@Test
	void allowsOperationsOnTwoSubfoldersOfTheSameLibrary(@TempDir Path folder) throws Exception {
		Path photos = folder.resolve("Fotos");

		try (var _ = holdElsewhere(ExecutionType.INVENTORY, photos.resolve("2025"))) {
			assertThatCode(() -> operationLockService.acquire(ExecutionType.INVENTORY, photos.resolve("2026")).close())
					.doesNotThrowAnyException();
		}
	}

	/** F: a move conflicts through its destination, not only its source. */
	@Test
	void refusesAMoveWhoseDestinationTreeIsBusy(@TempDir Path folder) throws Exception {
		Path source = folder.resolve("Fotos").resolve("a.jpg");
		Path videos = folder.resolve("Videos");

		try (var _ = holdElsewhere(ExecutionType.CONVERSION, videos)) {
			Path insideVideos = videos.resolve("a.jpg");

			assertThatExceptionOfType(OperationLockException.class)
					.isThrownBy(() -> operationLockService.acquire(ExecutionType.ORGANIZATION, source, insideVideos));
		}
	}

	/**
	 * G: a move across volumes takes both hierarchies without making either
	 * volume exclusive - another operation on each side still proceeds.
	 */
	@Test
	void takesBothHierarchiesOfACrossVolumeMoveWithoutLockingEitherVolume(@TempDir Path first,
			@TempDir Path second) throws Exception {
		Path source = first.resolve("Fotos").resolve("a.jpg");
		Path target = second.resolve("Backup").resolve("a.jpg");

		try (var _ = holdElsewhere(ExecutionType.ORGANIZATION, source, target)) {
			assertThatCode(() -> operationLockService.acquire(ExecutionType.INVENTORY, first.resolve("Outra")).close())
					.doesNotThrowAnyException();
			assertThatCode(() -> operationLockService.acquire(ExecutionType.INVENTORY, second.resolve("Outra")).close())
					.doesNotThrowAnyException();
		}
	}

	/**
	 * The whole point of the shared mode: an ancestor is held by many operations
	 * at once. Three descendants of one folder all hold it, and none waits.
	 */
	@Test
	void letsManyOperationsShareTheSameAncestorAtOnce(@TempDir Path folder) throws Exception {
		try (var _ = holdElsewhere(ExecutionType.INVENTORY, folder.resolve("a"));
				var _ = holdElsewhere(ExecutionType.INVENTORY, folder.resolve("b"));
				var _ = holdElsewhere(ExecutionType.INVENTORY, folder.resolve("c"))) {
			assertThat(operationLockService.isBusy(folder)).isTrue();
		}
	}

	/**
	 * Ownership is checked by mode, not by count. A shared hold on an ancestor
	 * must not be mistaken for the exclusive hold on the scope itself.
	 */
	@Test
	void confirmsOwnershipOfBothModesTogether(@TempDir Path folder) {
		Path scope = folder.resolve("Fotos").resolve("2026");

		try (OperationLock lock = operationLockService.acquire(ExecutionType.INVENTORY, scope)) {
			assertThat(operationLockService.stillHolds(lock)).isTrue();
		}
	}

	/** Closing releases the shared ancestors too, not only the exclusive scope. */
	@Test
	void releasesSharedAncestorsWhenTheConnectionCloses(@TempDir Path folder) {
		Path scope = folder.resolve("Fotos");

		operationLockService.acquire(ExecutionType.INVENTORY, scope.resolve("2026")).close();

		assertThat(operationLockService.isBusy(folder)).isFalse();

		assertThatCode(() -> operationLockService.acquire(ExecutionType.ORGANIZATION, scope).close())
				.doesNotThrowAnyException();
	}

	/**
	 * The order the caller happens to list its paths in must not change the order
	 * the locks are taken in - that identity is what rules out deadlock between
	 * two operations wanting the same pair.
	 */
	@Test
	void acquiresInTheSameOrderWhicheverOrderTheCallerAsked(@TempDir Path folder) {
		Path source = folder.resolve("origem");
		Path target = folder.resolve("destino");

		assertThat(OperationPathKey.chainOf(List.of(source, target)))
				.containsExactlyElementsOf(OperationPathKey.chainOf(List.of(target, source)));
	}

	@Test
	void ignoresNullPathsAmongTheOnesGiven(@TempDir Path folder) {
		assertThatCode(() -> operationLockService.acquire(ExecutionType.INVENTORY, null, folder).close())
				.doesNotThrowAnyException();
	}

	/**
	 * One call for the assertion to watch, so the refusal cannot be confused with
	 * a failure in building the arguments.
	 */
	private void acquireWaiting(Duration timeout, Path folder) {
		operationLockService.acquireWithin(timeout, ExecutionType.ORGANIZATION, folder).close();
	}

	/**
	 * Holds a lock in a thread of its own, which is what a second operation is.
	 *
	 * <p>
	 * Nested acquires on one thread reuse that thread's session and therefore
	 * reenter - deliberately, because a service that locks and then calls another
	 * service locking the same paths is normal here. So a test that wants a
	 * genuine conflict has to arrange a genuine second holder, exactly as two
	 * processes would be.
	 */
	private AutoCloseable holdElsewhere(ExecutionType executionType, Path... paths) throws Exception {
		CountDownLatch acquired = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Thread holder = new Thread(() -> {
			try (var _ = operationLockService.acquire(executionType, paths)) {
				acquired.countDown();

				release.await();
			} catch (Throwable throwable) {
				failure.set(throwable);

				acquired.countDown();
			}
		});

		holder.start();

		assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(failure.get()).isNull();

		return () -> {
			release.countDown();

			holder.join();
		};
	}

	@Test
	void refusesToLockNothing() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> operationLockService.acquire(ExecutionType.INVENTORY, new Path[0]));
	}
}