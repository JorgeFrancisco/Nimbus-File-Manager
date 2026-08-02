package br.com.jorgemelo.nimbusfilemanager.inventory.application.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.ExecutionContext;

import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.scanner.FileScanner;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;

@ExtendWith(MockitoExtension.class)
class InventoryFileItemReaderTest {

	@Mock
	private FileScanner fileScanner;

	@Mock
	private ScanExclusionService scanExclusionService;

	private final OperationLockService operationLockService = new OperationLockService();

	@Test
	void shouldReadEveryFileFromTheScannerStreamThenNull() throws Exception {
		Path first = Path.of("C:/media/a.jpg");
		Path second = Path.of("C:/media/b.jpg");

		when(scanExclusionService.excludedExtensions()).thenReturn(List.of());
		when(scanExclusionService.excludedFolders()).thenReturn(List.of());
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of(first, second));

		InventoryFileItemReader reader = reader();

		try {
			reader.open(new ExecutionContext());

			Assertions.assertThat(reader.read()).isEqualTo(first);
			Assertions.assertThat(reader.read()).isEqualTo(second);
			Assertions.assertThat(reader.read()).isNull();

			reader.update(new ExecutionContext());
		} finally {
			reader.close();
		}
	}

	@Test
	void openShouldAcquireOperationLockAndCloseShouldReleaseIt() throws Exception {
		when(scanExclusionService.excludedExtensions()).thenReturn(List.of());
		when(scanExclusionService.excludedFolders()).thenReturn(List.of());
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of());

		InventoryFileItemReader reader = reader();

		reader.open(new ExecutionContext());

		// OperationLockService allows same-thread reentrancy (see
		// OperationLock.ownerThreadId), so
		// the conflict must be provoked from a different thread.
		AtomicReference<Throwable> conflict = new AtomicReference<>();

		CountDownLatch attempted = new CountDownLatch(1);

		Thread otherThread = new Thread(() -> {
			try {
				operationLockService.acquire(ExecutionType.INVENTORY, Path.of("C:/media"));
			} catch (Throwable e) {
				conflict.set(e);
			} finally {
				attempted.countDown();
			}
		});

		otherThread.start();

		Assertions.assertThat(attempted.await(2, TimeUnit.SECONDS)).isTrue();

		otherThread.join();

		Assertions.assertThat(conflict.get()).isInstanceOf(OperationLockException.class);

		reader.close();

		try (var ignored = operationLockService.acquire(ExecutionType.INVENTORY, Path.of("C:/media"))) {
			Assertions.assertThat(ignored).isNotNull();
		}
	}

	@Test
	void closeShouldBeSafeWhenOpenWasNeverCalled() {
		Assertions.assertThatCode(() -> reader().close()).doesNotThrowAnyException();
	}

	@Test
	void openShouldSkipCleanlyWhenAnotherOperationHoldsTheLock() throws Exception {
		// Another thread (e.g. a running organization) holds a conflicting lock on the
		// same
		// path. open() must not fail the job; it skips the scan and reads nothing.
		CountDownLatch acquired = new CountDownLatch(1);

		CountDownLatch release = new CountDownLatch(1);

		Thread holder = new Thread(() -> {
			try (var _ = operationLockService.acquire(ExecutionType.ORGANIZATION, Path.of("C:/media"))) {
				acquired.countDown();
				release.await(2, TimeUnit.SECONDS);
			} catch (Exception _) {
				// test thread interrupted; nothing to do
			}
		});

		holder.start();

		Assertions.assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue();

		InventoryFileItemReader reader = reader();

		try {
			Assertions.assertThatCode(() -> reader.open(new ExecutionContext())).doesNotThrowAnyException();
			Assertions.assertThat(reader.read()).isNull();
		} finally {
			reader.close();
			release.countDown();
			holder.join();
		}
	}

	@Test
	void closeShouldReleaseTheLockEvenWhenClosingTheStreamThrows() throws Exception {
		when(scanExclusionService.excludedExtensions()).thenReturn(List.of());
		when(scanExclusionService.excludedFolders()).thenReturn(List.of());

		// A folder-walk stream whose close() fails (an I/O error tearing down the
		// walk).
		Stream<Path> failingStream = Stream.<Path>of().onClose(() -> {
			throw new RuntimeException("I/O error closing the folder walk");
		});

		when(fileScanner.stream(any(), any())).thenReturn(failingStream);

		InventoryFileItemReader reader = reader();

		reader.open(new ExecutionContext());

		// close() propagates the stream failure, but must still release the lock first.
		Assertions.assertThatThrownBy(reader::close).isInstanceOf(RuntimeException.class);

		// Proof the INVENTORY lock was released despite the failure: another thread can
		// take it now (a leaked lock would make this acquisition throw).
		AtomicBoolean acquired = new AtomicBoolean(false);

		Thread other = new Thread(() -> {
			try (var _ = operationLockService.acquire(ExecutionType.INVENTORY, Path.of("C:/media"))) {
				acquired.set(true);
			} catch (OperationLockException _) {
				acquired.set(false);
			}
		});

		other.start();
		other.join(2000);

		Assertions.assertThat(acquired.get()).isTrue();
	}

	/**
	 * A scan that cannot even start must hand the lock back. Keeping it would
	 * refuse every later inventory - and any organization on the same tree - for
	 * as long as the process lives, over a failure that already ended.
	 */
	@Test
	void openShouldReleaseTheLockWhenTheScanCannotStart() throws Exception {
		when(scanExclusionService.excludedExtensions()).thenReturn(List.of());
		when(scanExclusionService.excludedFolders()).thenReturn(List.of());
		when(fileScanner.stream(any(), any())).thenThrow(new IllegalStateException("Could not scan path"));

		InventoryFileItemReader reader = reader();

		ExecutionContext context = new ExecutionContext();

		Assertions.assertThatThrownBy(() -> reader.open(context)).isInstanceOf(IllegalStateException.class);

		AtomicBoolean acquired = new AtomicBoolean(false);

		Thread other = new Thread(() -> {
			try (var _ = operationLockService.acquire(ExecutionType.INVENTORY, Path.of("C:/media"))) {
				acquired.set(true);
			} catch (OperationLockException _) {
				acquired.set(false);
			}
		});

		other.start();
		other.join(2000);

		Assertions.assertThat(acquired.get()).isTrue();
	}

	private InventoryFileItemReader reader() {
		return new InventoryFileItemReader(fileScanner, scanExclusionService, operationLockService, "C:/media", "true",
				"false");
	}
}