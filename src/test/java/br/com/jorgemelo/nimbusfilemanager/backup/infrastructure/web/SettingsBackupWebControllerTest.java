package br.com.jorgemelo.nimbusfilemanager.backup.infrastructure.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;

/**
 * The guards around a destructive action. Restoring replaces the whole catalog,
 * so what the screen must never do is start one silently or leave the operator
 * guessing whether it happened.
 */
class SettingsBackupWebControllerTest {

	private static final String NAME = "nimbus-catalog-20260801-060000.zip";

	private final CatalogBackupService catalogBackupService = mock(CatalogBackupService.class);
	private final ExecutionQueryService executionQueryService = mock(ExecutionQueryService.class);

	private final CatalogBackupAsyncRunner asyncRunner = mock(CatalogBackupAsyncRunner.class);

	private final SettingsBackupWebController controller = new SettingsBackupWebController(
			catalogBackupService, asyncRunner, executionQueryService);

	private static ExecutionResponse execution(String type) {
		return new ExecutionResponse(1L, type, "RUNNING", LocalDateTime.now(), null, "src", null, 1, 1, 0, 0, 0, 0,
				null, null, "running", false);
	}

	/**
	 * The work runs in the background, so the answer says it started rather than
	 * that it finished - a few hundred MB take about a minute, and a request held
	 * open for that long is what made the screen look hung.
	 */
	@Test
	void creatingABackupStartsItInTheBackground() {
		when(asyncRunner.start()).thenReturn(true);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		String view = controller.createBackup(redirect);

		verify(asyncRunner).create();

		Assertions.assertThat(view).isEqualTo(SharedConstants.REDIRECT_SETTINGS);
		Assertions.assertThat(redirect.getFlashAttributes()).containsKey(SharedConstants.ATTR_SUCCESS);
	}

	/** One operation at a time: a second request has to be refused, not queued. */
	@Test
	void refusesASecondOperationWhileOneIsRunning() {
		when(asyncRunner.start()).thenReturn(false);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.createBackup(redirect);

		verify(asyncRunner, never()).create();

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey(SharedConstants.ATTR_ERROR);
	}

	/**
	 * Cancelling costs nothing while a dump is running: the database was only
	 * read, and the half-written file goes with it.
	 */
	@Test
	void cancelsARunningBackup() {
		when(asyncRunner.cancel()).thenReturn(true);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.cancelBackup(redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey(SharedConstants.ATTR_SUCCESS);
	}

	/**
	 * A restore drops objects to recreate them, so it is never cancellable - and
	 * the screen has to say why rather than appear to have done nothing.
	 */
	@Test
	void explainsWhenThereIsNothingToCancel() {
		when(asyncRunner.cancel()).thenReturn(false);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.cancelBackup(redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey(SharedConstants.ATTR_ERROR);
	}

	/**
	 * A backup that could not be written is the one moment the operator most needs
	 * to be told: they would otherwise believe they are protected.
	 */
	@Test
	void aFailedBackupSaysWhyInsteadOfPassingSilently() {
		when(catalogBackupService.create()).thenThrow(new IllegalStateException("no space left on device"));

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.createBackup(redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey(SharedConstants.ATTR_ERROR);
	}

	@Test
	void restoringStartsInTheBackground() {
		when(asyncRunner.start()).thenReturn(true);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.restoreBackup(NAME, redirect);

		verify(asyncRunner).restore(NAME);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey(SharedConstants.ATTR_SUCCESS);
	}

	/**
	 * Any execution, and not only an inventory. The restore recreates every table
	 * the backup carries - the execution table included - so whatever is running
	 * would be reporting progress into a row being replaced underneath it. The
	 * type of work does not enter into it.
	 */
	@Test
	void restoringIsBlockedWhileAnyExecutionIsActive() {
		when(executionQueryService.active()).thenReturn(Optional.of(execution("CONVERSION")));

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.restoreBackup(NAME, redirect);

		verify(catalogBackupService, never()).restore(NAME);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey(SharedConstants.ATTR_ERROR);
	}

	/**
	 * The rule it replaced only knew about inventories, which let a restore start
	 * beside a conversion, a reconcile or an organization run.
	 */
	@Test
	void restoringIsBlockedWhileAnInventoryRuns() {
		when(executionQueryService.active()).thenReturn(Optional.of(execution("INVENTORY")));

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.restoreBackup(NAME, redirect);

		verify(catalogBackupService, never()).restore(NAME);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey(SharedConstants.ATTR_ERROR);
	}

	@Test
	void aRefusedRestoreSaysWhy() {
		doThrow(new IllegalArgumentException("Backup was taken from schema 12, this database is on 13"))
				.when(catalogBackupService).restore(NAME);

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.restoreBackup(NAME, redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey(SharedConstants.ATTR_ERROR);
	}

	@Test
	void deletingRemovesTheFileAndSaysSo() {
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.deleteBackup(NAME, redirect);

		verify(catalogBackupService).delete(NAME);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey(SharedConstants.ATTR_SUCCESS);
	}

	@Test
	void aFailedDeleteSaysWhy() {
		doThrow(new IllegalArgumentException("Not a backup of this installation: x")).when(catalogBackupService)
				.delete("x");

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.deleteBackup("x", redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey(SharedConstants.ATTR_ERROR);
	}
}