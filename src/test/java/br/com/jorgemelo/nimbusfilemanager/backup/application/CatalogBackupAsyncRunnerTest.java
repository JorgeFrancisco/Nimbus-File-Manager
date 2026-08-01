package br.com.jorgemelo.nimbusfilemanager.backup.application;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.BackupFile;

/**
 * One operation at a time, and what the screen is told about it.
 *
 * <p>
 * A backup taken while a restore is replacing the same tables would capture
 * neither the old catalog nor the new one, so the second request has to be
 * refused rather than queued. And a failure has to survive the background
 * thread: the request that started it is long gone, so the only way the
 * operator ever learns is the message kept here.
 */
class CatalogBackupAsyncRunnerTest {

	private static final String NAME = "nimbus-catalog-20260801-060000.zip";

	private final CatalogBackupService backupService = mock(CatalogBackupService.class);

	private final CatalogBackupAsyncRunner runner = new CatalogBackupAsyncRunner(backupService, new BackupProgress());

	@Test
	void refusesToStartASecondOperationWhileOneIsRunning() {
		Assertions.assertThat(runner.start()).isTrue();
		Assertions.assertThat(runner.start()).isFalse();
		Assertions.assertThat(runner.isRunning()).isTrue();
	}

	@Test
	void reportsTheBackupItWroteAndStopsRunning() {
		when(backupService.create()).thenReturn(new BackupFile(NAME, 2048, LocalDateTime.now()));

		runner.start();
		runner.create();

		Assertions.assertThat(runner.lastResult()).isEqualTo(NAME);
		Assertions.assertThat(runner.lastError()).isNull();
		Assertions.assertThat(runner.isRunning()).isFalse();
	}

	/**
	 * The failure has to outlive the thread that hit it. Without this the screen
	 * would show a finished operation and no reason, which reads as success.
	 */
	@Test
	void keepsTheReasonABackupFailed() {
		when(backupService.create()).thenThrow(new IllegalStateException("the disk is full"));

		runner.start();
		runner.create();

		Assertions.assertThat(runner.lastError()).isEqualTo("the disk is full");
		Assertions.assertThat(runner.lastResult()).isNull();
		Assertions.assertThat(runner.isRunning()).isFalse();
	}

	@Test
	void reportsTheBackupItRestored() {
		runner.start();
		runner.restore(NAME);

		Assertions.assertThat(runner.lastResult()).isEqualTo(NAME);
		Assertions.assertThat(runner.lastError()).isNull();
	}

	@Test
	void keepsTheReasonARestoreFailed() {
		doThrow(new IllegalArgumentException("newer than this installation")).when(backupService).restore(anyString());

		runner.start();
		runner.restore(NAME);

		Assertions.assertThat(runner.lastError()).isEqualTo("newer than this installation");
		Assertions.assertThat(runner.isRunning()).isFalse();
	}

	/**
	 * Cancelling a dump costs nothing: the database was only read, and the
	 * half-written file goes with it.
	 */
	@Test
	void cancelsABackupInFlight() {
		when(backupService.cancel()).thenReturn(true);

		runner.start();

		Assertions.assertThat(runner.cancel()).isTrue();
	}

	/**
	 * A restore drops objects to recreate them. Stopping halfway leaves the catalog
	 * neither as it was nor as it was becoming, so the answer is no - and the tool
	 * is never even asked.
	 */
	@Test
	void refusesToCancelARestore() {
		doAnswer(_ -> {
			Assertions.assertThat(runner.cancel()).isFalse();

			return null;
		}).when(backupService).restore(anyString());

		runner.start();
		runner.restore(NAME);

		verify(backupService, never()).cancel();
	}

	/** A new run starts clean, or the last failure would haunt the next screen. */
	@Test
	void clearsWhatThePreviousRunLeftBehind() {
		when(backupService.create()).thenThrow(new IllegalStateException("the disk is full"));

		runner.start();
		runner.create();

		Assertions.assertThat(runner.start()).isTrue();
		Assertions.assertThat(runner.lastError()).isNull();
		Assertions.assertThat(runner.lastResult()).isNull();
	}
}