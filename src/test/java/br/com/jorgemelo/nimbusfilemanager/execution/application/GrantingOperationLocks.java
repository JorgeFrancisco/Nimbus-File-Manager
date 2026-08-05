package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.SQLException;

import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.AdvisoryPathLockRepository;

/**
 * An {@link OperationLockService} that hands out every lock it is asked for,
 * for the many unit tests whose subject is something else entirely - an
 * organization run, a quarantine purge - and that only need the lock not to be
 * in the way.
 *
 * <p>
 * Exclusion itself is a property of PostgreSQL now, so asserting it belongs in
 * an integration test with two real sessions, not here. Faking a grant keeps
 * those unit tests from needing a database to prove something they never had
 * anything to say about.
 *
 * <p>
 * Lenient by design: each caller exercises a different corner of the lock, so
 * requiring every stub to be used would make one shared helper impossible.
 */
public final class GrantingOperationLocks {

	private GrantingOperationLocks() {
	}

	/**
	 * The opposite service, for the tests whose subject <em>is</em> the refusal -
	 * an organization that must report "rejected" when the tree is busy. They used
	 * to arrange that by holding the real lock on another thread; with exclusion
	 * living in the database, the honest unit-level arrangement is a repository
	 * that says the keys are taken.
	 */
	public static OperationLockService refusing() {
		try {
			AdvisoryPathLockRepository repository = mock(AdvisoryPathLockRepository.class);

			lenient().when(repository.openLockSession()).thenReturn(mock(Connection.class));
			lenient().when(repository.tryLockAll(any(), anyCollection())).thenReturn(false);
			lenient().when(repository.lockedByAnyone(anyCollection())).thenReturn(true);

			return new OperationLockService(repository);
		} catch (SQLException exception) {
			throw new IllegalStateException("Stubbing cannot fail", exception);
		}
	}

	public static OperationLockService granting() {
		try {
			AdvisoryPathLockRepository repository = mock(AdvisoryPathLockRepository.class);

			lenient().when(repository.openLockSession()).thenReturn(mock(Connection.class));
			lenient().when(repository.tryLockAll(any(), anyCollection())).thenReturn(true);
			lenient().when(repository.stillHolds(any(), anyCollection())).thenReturn(true);
			lenient().when(repository.lockedByAnyone(anyCollection())).thenReturn(false);

			return new OperationLockService(repository);
		} catch (SQLException exception) {
			throw new IllegalStateException("Stubbing cannot fail", exception);
		}
	}
}