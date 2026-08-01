package br.com.jorgemelo.nimbusfilemanager.backup.infrastructure.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;

/**
 * The guards around a destructive action. Restoring replaces the whole catalog,
 * so what the screen must never do is start one silently or leave the operator
 * guessing whether it happened.
 */
class SettingsBackupWebControllerTest {

	private static final String NAME = "nimbus-catalog-20260801-060000.zip";

	private final CatalogBackupService catalogBackupService = mock(CatalogBackupService.class);
	private final InventoryRunningState inventoryRunningState = mock(InventoryRunningState.class);

	private final CatalogBackupAsyncRunner asyncRunner = mock(CatalogBackupAsyncRunner.class);

	private final SettingsBackupWebController controller = new SettingsBackupWebController(catalogBackupService, asyncRunner,
			inventoryRunningState);

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
	 * An inventory writes rows while it runs; emptying the catalog under it would
	 * end with neither the old catalog nor the restored one.
	 */
	@Test
	void restoringIsBlockedWhileAnInventoryRuns() {
		when(inventoryRunningState.isRunning()).thenReturn(true);

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