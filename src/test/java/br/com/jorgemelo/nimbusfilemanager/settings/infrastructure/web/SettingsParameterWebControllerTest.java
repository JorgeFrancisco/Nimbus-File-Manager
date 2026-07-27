package br.com.jorgemelo.nimbusfilemanager.settings.infrastructure.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.InventoryWatchService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.LibrarySwitchService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.QuarantineFolderPolicy;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;

/**
 * The quarantine policy is the real one rather than a mock: what matters here
 * is that a refused folder never reaches {@code AppSettingService.update} and
 * that the user gets the reason back, which a stubbed policy could not show.
 */
class SettingsParameterWebControllerTest {

	private final AppSettingService settings = mock(AppSettingService.class);
	private final InventoryWatchService watcher = mock(InventoryWatchService.class);
	private final LibrarySwitchService librarySwitch = mock(LibrarySwitchService.class);
	private final ExecutionQueryService executionQueryService = mock(ExecutionQueryService.class);
	private final InventoryRunningState inventoryRunningState = new InventoryRunningState(executionQueryService);
	private final QuarantineFolderPolicy quarantineFolderPolicy = new QuarantineFolderPolicy(settings);
	private final SettingsParameterWebController controller = new SettingsParameterWebController(settings, watcher,
			librarySwitch, inventoryRunningState, quarantineFolderPolicy);

	private final TestingAuthenticationToken authentication = new TestingAuthenticationToken("Admin@Example.com", "pw");

	private static ExecutionResponse inventoryExecution() {
		return new ExecutionResponse(1L, "INVENTORY", "PROCESSING_FILES", LocalDateTime.now(), null, "src", null, 1, 1,
				0, 0, 0, 0, null, null, "running", false);
	}

	@Test
	void updateShouldConfirmAndStartLibrarySwitch() {
		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("C:/old-media");

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		Assertions.assertThat(
				controller.update("nimbus-file-manager.inventory.watch-folder", "C:/media", true, authentication,
						redirect))
				.isEqualTo("redirect:/app/settings");

		verify(librarySwitch).validateNewFolder("C:/media");
		verify(librarySwitch).switchLibrary("C:/old-media", "C:/media", "Admin@Example.com");

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("success");

		doThrow(new IllegalArgumentException("invalid")).when(settings).update("bad", "value", "system");

		redirect = new RedirectAttributesModelMap();

		controller.update("bad", "value", false, null, redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).extractingByKey("error").isEqualTo("invalid");
	}

	@Test
	void updateShouldRejectUnconfirmedLibrarySwitch() {
		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("C:/old-media");

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.update(SettingsConstants.WATCH_FOLDER, "C:/new-media", false, authentication, redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).extractingByKey("error")
				.isEqualTo("Confirme a troca da biblioteca monitorada.");

		verify(librarySwitch, never()).switchLibrary(ArgumentMatchers.anyString(),
				ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
	}

	@Test
	void updatingWatchSettingTriggersReconfigure() {
		var auth = new TestingAuthenticationToken("admin@x", "pw");
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.update("nimbus-file-manager.inventory.watch-interval", "10", false, auth, redirect);

		verify(settings).update("nimbus-file-manager.inventory.watch-interval", "10", "admin@x");
		verify(watcher).reconfigureAndInventory();

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("success");
	}

	@Test
	void updatingSameWatchFolderSkipsLibrarySwitch() {
		var auth = new TestingAuthenticationToken("admin@x", "pw");
		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("C:/media");

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.update(SettingsConstants.WATCH_FOLDER, " C:/media ", false, auth, redirect);

		verify(librarySwitch, never()).switchLibrary(ArgumentMatchers.anyString(),
				ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
		verify(settings).update(SettingsConstants.WATCH_FOLDER, " C:/media ", "admin@x");
		verify(watcher).reconfigureAndInventory();
	}

	@Test
	void systemSettingUpdateBlockedWhileInventoryRunning() {
		var auth = new TestingAuthenticationToken("admin@x", "pw");
		when(executionQueryService.active()).thenReturn(Optional.of(inventoryExecution()));

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.update("nimbus-file-manager.inventory.watch-recursive", "false", false, auth, redirect);

		Assertions.assertThat(redirect.getFlashAttributes().get("error").toString()).contains("inventário");

		verify(settings, never()).update(ArgumentMatchers.anyString(),
				ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
		verify(watcher, never()).reconfigureAndInventory();
	}

	@Test
	void updatesAnOrdinaryParameter() {
		var auth = new TestingAuthenticationToken("admin", "password");

		Assertions.assertThat(controller.update("nimbus-file-manager.api.max-page-size", "250", false, auth,
				new RedirectAttributesModelMap())).isEqualTo("redirect:/app/settings");

		verify(settings).update("nimbus-file-manager.api.max-page-size", "250", "admin");
	}

	/**
	 * Everything under the quarantine root is skipped by every scan, so saving it
	 * inside the library would silently hide that part of the library. The refusal
	 * has to reach the screen with the reason, not only the log.
	 */
	@Test
	void refusesAQuarantineInsideTheMonitoredLibrary(@TempDir Path tmp) {
		Path library = tmp.resolve("library");

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(library.toString());

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.update(SettingsConstants.TRASH_FOLDER, library.resolve("trash").toString(), false, authentication,
				redirect);

		Assertions.assertThat(redirect.getFlashAttributes().get("error").toString())
				.contains("não pode ficar dentro da biblioteca monitorada");

		verify(settings, never()).update(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
				ArgumentMatchers.anyString());
	}

	@Test
	void acceptsAQuarantineOutsideTheMonitoredLibrary(@TempDir Path tmp) {
		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(tmp.resolve("library").toString());

		String quarantine = tmp.resolve("trash").toString();

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.update(SettingsConstants.TRASH_FOLDER, quarantine, false, authentication, redirect);

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("success");

		verify(settings).update(SettingsConstants.TRASH_FOLDER, quarantine, "Admin@Example.com");
	}

	/**
	 * The same overlap seen from the other side: switching the library to a folder
	 * that already contains the quarantine is refused before the switch starts -
	 * and the switch is asynchronous, so a check made any later would have no way
	 * back to the user.
	 */
	@Test
	void refusesALibrarySwitchThatWouldContainTheQuarantine(@TempDir Path tmp) {
		Path library = tmp.resolve("library");

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("C:/old-media");
		when(settings.stringValue(SettingsConstants.TRASH_FOLDER, ""))
				.thenReturn(library.resolve("trash").toString());

		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		controller.update(SettingsConstants.WATCH_FOLDER, library.toString(), true, authentication, redirect);

		Assertions.assertThat(redirect.getFlashAttributes().get("error").toString())
				.contains("não pode ficar dentro da biblioteca monitorada");

		verify(librarySwitch, never()).switchLibrary(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
				ArgumentMatchers.anyString());
	}
}