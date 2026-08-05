package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.AdvisoryPathLockRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;

/**
 * What happens to file exclusion when the database is not there.
 *
 * <p>
 * Whether two paths conflict is PostgreSQL's answer and is asserted against a
 * real server in {@code OperationLockServiceIntegrationTest}. What is left for
 * a unit test is the part no live database will produce on demand: the moment
 * the connection fails. It matters because the answer must always be the safe
 * one - refuse the lock, deny ownership - and never "carry on, probably fine".
 */
class OperationLockServiceTest {

	private final AdvisoryPathLockRepository advisoryPathLockRepository = mock(AdvisoryPathLockRepository.class);

	private final OperationLockService service = new OperationLockService(advisoryPathLockRepository);

	@Test
	void refusesTheLockWhenTheDatabaseCannotBeReached(@TempDir Path folder) throws Exception {
		when(advisoryPathLockRepository.openLockSession()).thenThrow(new SQLException("connection refused"));

		assertThatExceptionOfType(OperationLockException.class)
				.isThrownBy(() -> service.acquire(ExecutionType.INVENTORY, folder))
				.withMessageContaining("connection refused");
	}

	@Test
	void refusesTheLockAndReturnsTheConnectionWhenTakingTheKeysFails(@TempDir Path folder) throws Exception {
		Connection session = mock(Connection.class);

		when(advisoryPathLockRepository.openLockSession()).thenReturn(session);
		when(advisoryPathLockRepository.tryLockAll(any(), anyCollection())).thenThrow(new SQLException("gone"));

		assertThatExceptionOfType(OperationLockException.class)
				.isThrownBy(() -> service.acquire(ExecutionType.INVENTORY, folder));

		verify(session).close();
	}

	/**
	 * Failing to hand the connection back must not replace the original failure:
	 * the caller needs to hear why the lock was refused, not why the cleanup was
	 * untidy.
	 */
	@Test
	void keepsTheOriginalFailureWhenTheConnectionAlsoFailsToClose(@TempDir Path folder) throws Exception {
		Connection session = mock(Connection.class);

		when(advisoryPathLockRepository.openLockSession()).thenReturn(session);
		when(advisoryPathLockRepository.tryLockAll(any(), anyCollection())).thenThrow(new SQLException("gone"));
		doThrow(new SQLException("cannot close")).when(session).close();

		assertThatExceptionOfType(OperationLockException.class)
				.isThrownBy(() -> service.acquire(ExecutionType.INVENTORY, folder)).withMessageContaining("gone");
	}

	@Test
	void refusesToAnswerWhetherATreeIsBusyWhenTheDatabaseIsGone(@TempDir Path folder) throws Exception {
		when(advisoryPathLockRepository.lockedByAnyone(anyCollection())).thenThrow(new SQLException("gone"));

		assertThatExceptionOfType(OperationLockException.class).isThrownBy(() -> service.isBusy(folder));
	}

	/**
	 * The check every long operation makes before touching another file. An
	 * unreachable database is indistinguishable from a server that restarted and
	 * dropped the locks, so both have to answer no - answering yes would let a
	 * worker keep moving files with no exclusion at all.
	 */
	@Test
	void deniesOwnershipWhenTheDatabaseCannotConfirmIt(@TempDir Path folder) throws Exception {
		Connection session = mock(Connection.class);

		when(advisoryPathLockRepository.openLockSession()).thenReturn(session);
		when(advisoryPathLockRepository.tryLockAll(any(), anyCollection())).thenReturn(true);
		when(advisoryPathLockRepository.stillHolds(any(), anyCollection())).thenThrow(new SQLException("gone"));

		OperationLock lock = service.acquire(ExecutionType.INVENTORY, folder);

		assertThat(service.stillHolds(lock)).isFalse();
	}

	@Test
	void closesQuietlyWhenReleasingCannotReachTheDatabase(@TempDir Path folder) throws Exception {
		Connection session = mock(Connection.class);

		when(advisoryPathLockRepository.openLockSession()).thenReturn(session);
		when(advisoryPathLockRepository.tryLockAll(any(), anyCollection())).thenReturn(true);
		doThrow(new SQLException("gone")).when(advisoryPathLockRepository).unlockAll(session);

		OperationLock lock = service.acquire(ExecutionType.INVENTORY, folder);

		assertThatCode(lock::close).doesNotThrowAnyException();
	}

	/**
	 * A waiting acquire has to answer an interrupt, not swallow it: the thread is
	 * being asked to stop - a shutdown, usually - and sleeping out the rest of a
	 * ten-minute wait would hold the process open for no reason. The interrupt
	 * flag is restored so whoever asked can tell it worked.
	 */
	@Test
	void refusesAndKeepsTheInterruptFlagWhenTheWaitIsInterrupted(@TempDir Path folder) throws Exception {
		Connection session = mock(Connection.class);

		when(advisoryPathLockRepository.openLockSession()).thenReturn(session);
		when(advisoryPathLockRepository.tryLockAll(any(), anyCollection())).thenReturn(false);

		Thread.currentThread().interrupt();

		try {
			assertThatExceptionOfType(OperationLockException.class).isThrownBy(() -> acquireWaiting(folder))
					.withMessageContaining("Interrupted");

			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		} finally {
			Thread.interrupted();
		}
	}

	/**
	 * A nested acquire that fails must not take the session with it - the outer
	 * lock is still holding it, and closing it there would release locks the
	 * caller still believes it owns.
	 */
	private void acquireWaiting(Path folder) {
		service.acquireWithin(Duration.ofSeconds(30), ExecutionType.INVENTORY, folder).close();
	}

	@Test
	void keepsTheSessionOpenWhenANestedAcquireFails(@TempDir Path folder) throws Exception {
		Connection session = mock(Connection.class);

		when(advisoryPathLockRepository.openLockSession()).thenReturn(session);
		when(advisoryPathLockRepository.tryLockAll(any(), anyCollection())).thenReturn(true)
				.thenThrow(new SQLException("gone"));

		try (var _ = service.acquire(ExecutionType.ORGANIZATION, folder)) {
			assertThatExceptionOfType(OperationLockException.class)
					.isThrownBy(() -> service.acquire(ExecutionType.ORGANIZATION, folder));

			verify(session, never()).close();
		}
	}

	/**
	 * Closing twice happens - a {@code try-with-resources} inside another one,
	 * both naming the same lock - and the second close has no session left to
	 * return.
	 */
	@Test
	void ignoresAReleaseWhenNoSessionIsHeld(@TempDir Path folder) throws Exception {
		Connection session = mock(Connection.class);

		when(advisoryPathLockRepository.openLockSession()).thenReturn(session);
		when(advisoryPathLockRepository.tryLockAll(any(), anyCollection())).thenReturn(true);

		OperationLock lock = service.acquire(ExecutionType.INVENTORY, folder);

		lock.close();

		assertThatCode(lock::close).doesNotThrowAnyException();
	}
}