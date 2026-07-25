package br.com.jorgemelo.nimbusfilemanager.settings.infrastructure.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateExclusionService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.geolocation.infrastructure.web.GeoDatasetSettingsModel;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.InventoryWatchService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.constants.OrganizationConstants;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.LibrarySwitchService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.UpdatePreferencesForm;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;

class SettingsWebControllerTest {

	private final AppSettingService settings = mock(AppSettingService.class);
	private final InventoryWatchService watcher = mock(InventoryWatchService.class);
	private final UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
	private final LibrarySwitchService librarySwitch = mock(LibrarySwitchService.class);
	private final DuplicateExclusionService duplicateExclusions = mock(DuplicateExclusionService.class);
	private final ExecutionQueryService executionQueryService = mock(ExecutionQueryService.class);
	private final InventoryRunningState inventoryRunningState = new InventoryRunningState(executionQueryService);
	private final GeoDatasetSettingsModel geoDatasetSettingsModel = mock(GeoDatasetSettingsModel.class);
	private final SettingsWebController controller = new SettingsWebController(settings, watcher, librarySwitch,
			inventoryRunningState, duplicateExclusions, preferences, geoDatasetSettingsModel);
	private final TestingAuthenticationToken authentication = new TestingAuthenticationToken("Admin@Example.com", "pw");

	private static ExecutionResponse inventoryExecution() {
		return new ExecutionResponse(1L, "INVENTORY", "PROCESSING_FILES", LocalDateTime.now(), null, "src", null, 1, 1,
				0, 0, 0, 0, null, null, "running", false);
	}

	@Test
	void shouldRenderSystemAndPreferenceTabsWithSavedValues() {
		when(preferences.find("Admin@Example.com", "files")).thenReturn(Map.of("view", "large", "size", "100"));
		when(preferences.find("Admin@Example.com", OrganizationConstants.PAGE_KEY)).thenReturn(
				Map.of(OrganizationConstants.RECURSIVE, "false", OrganizationConstants.ALLOW_CONFLICTS, "true",
						OrganizationConstants.OVERWRITE_EXISTING, "true", OrganizationConstants.LAYOUT,
						OrganizationLayout.SUBCATEGORY_YEAR_MONTH_DAY.name(), OrganizationConstants.SIZE, "20"));
		when(preferences.find("Admin@Example.com", "layout"))
				.thenReturn(Map.of(SharedConstants.THEME_PREFERENCE_KEY, SharedConstants.THEME_DARK));

		ExtendedModelMap model = new ExtendedModelMap();

		Assertions.assertThat(controller.settings(authentication, model)).isEqualTo("app/settings");
		Assertions.assertThat(model).containsEntry("activeTab", "system").containsEntry("themeValue", "dark")
				.containsEntry("filesView", "large").containsEntry("filesSize", 100)
				.containsEntry("organizationRecursive", false).containsEntry("organizationAllowConflicts", true)
				.containsEntry("organizationOverwriteExisting", true)
				.containsEntry("organizationLayoutValue", OrganizationLayout.SUBCATEGORY_YEAR_MONTH_DAY)
				.containsEntry("organizationSize", 20);
		Assertions.assertThat(controller.preferences(authentication, model)).isEqualTo("app/settings");
		Assertions.assertThat(model).containsEntry("activeTab", "preferences");
	}

	@Test
	void shouldFallBackForMissingAndInvalidSavedValues() {
		when(preferences.find("system", "files")).thenReturn(Map.of("size", "invalid"));
		when(preferences.find("system", OrganizationConstants.PAGE_KEY))
				.thenReturn(Map.of(OrganizationConstants.RECURSIVE, "true", OrganizationConstants.LAYOUT,
						"invalid", OrganizationConstants.SIZE, " "));
		when(preferences.find("system", "layout")).thenReturn(Map.of());

		ExtendedModelMap model = new ExtendedModelMap();

		controller.preferences(null, model);

		Assertions.assertThat(model).containsEntry("themeValue", "light").containsEntry("filesView", "details")
				.containsEntry("filesSize", 20).containsEntry("organizationRecursive", true)
				.containsEntry("organizationAllowConflicts", false)
				.containsEntry("organizationOverwriteExisting", false)
				.containsEntry("organizationLayoutValue", OrganizationLayout.DEFAULT)
				.containsEntry("organizationSize", 50);
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
	void updatePreferencesShouldPersistAllExplicitValues() {
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		Assertions
				.assertThat(controller.updatePreferences(new UpdatePreferencesForm("small", 20,
						OrganizationLayout.YEAR_MONTH_DAY, true, true, true, 100, "dark"), authentication, redirect))
				.isEqualTo("redirect:/app/settings/preferences");

		verify(preferences).save("Admin@Example.com", "files", "view", "small");
		verify(preferences).save("Admin@Example.com", "files", "size", "20");
		verify(preferences).save("Admin@Example.com", "layout", SharedConstants.THEME_PREFERENCE_KEY, "dark");
		verify(preferences).save("Admin@Example.com", OrganizationConstants.PAGE_KEY,
				OrganizationConstants.LAYOUT, OrganizationLayout.YEAR_MONTH_DAY.name());
		verify(preferences).save("Admin@Example.com", OrganizationConstants.PAGE_KEY,
				OrganizationConstants.SIZE, "100");

		Assertions.assertThat(redirect.getFlashAttributes()).containsKey("success");
	}

	@Test
	void updatePreferencesShouldAcceptAbsentOptionalValues() {
		controller.updatePreferences(new UpdatePreferencesForm(null, null, null, false, false, false, null,
				"unexpected"),
				null, new RedirectAttributesModelMap());

		verify(preferences).save("system", "layout", SharedConstants.THEME_PREFERENCE_KEY, "light");
	}

	@Test
	void updatePreferencesBindsEveryFormFieldThroughRealHttp() throws Exception {
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
				.setViewResolvers(new InternalResourceViewResolver()).build();

		mockMvc.perform(post("/app/settings/preferences").param("filesView", "small").param("filesSize", "20")
				.param("organizationLayout", "YEAR_MONTH_DAY").param("organizationRecursive", "true")
				.param("organizationAllowConflicts", "true").param("organizationOverwriteExisting", "true")
				.param("organizationSize", "100").param("theme", "dark")).andExpect(status().is3xxRedirection());

		ArgumentCaptor<String> page = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
		verify(preferences, times(8)).save(eq("system"), page.capture(), key.capture(), value.capture());

		List<String> saved = new ArrayList<>();
		for (int index = 0; index < value.getAllValues().size(); index++) {
			saved.add(page.getAllValues().get(index) + "/" + key.getAllValues().get(index) + "/"
					+ value.getAllValues().get(index));
		}

		Assertions.assertThat(saved).containsExactlyInAnyOrder("files/view/small", "files/size/20",
				"layout/" + SharedConstants.THEME_PREFERENCE_KEY + "/dark",
				OrganizationConstants.PAGE_KEY + "/" + OrganizationConstants.RECURSIVE + "/true",
				OrganizationConstants.PAGE_KEY + "/" + OrganizationConstants.ALLOW_CONFLICTS + "/true",
				OrganizationConstants.PAGE_KEY + "/" + OrganizationConstants.OVERWRITE_EXISTING + "/true",
				OrganizationConstants.PAGE_KEY + "/" + OrganizationConstants.LAYOUT + "/YEAR_MONTH_DAY",
				OrganizationConstants.PAGE_KEY + "/" + OrganizationConstants.SIZE + "/100");
	}

	@Test
	void updatePreferencesSavesUncheckedCheckboxesAsFalseInsteadOfFailing() throws Exception {
		// Regression: an unchecked checkbox is absent from the POST, so record binding
		// sees null for the boolean flags. They must default to false (not a 400),
		// which is the "switch to dark mode and save" path where the organization
		// checkboxes are untouched.
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
				.setViewResolvers(new InternalResourceViewResolver()).build();

		mockMvc.perform(post("/app/settings/preferences").param("theme", "dark"))
				.andExpect(status().is3xxRedirection());

		ArgumentCaptor<String> page = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
		verify(preferences, times(4)).save(eq("system"), page.capture(), key.capture(), value.capture());

		List<String> saved = new ArrayList<>();
		for (int index = 0; index < value.getAllValues().size(); index++) {
			saved.add(page.getAllValues().get(index) + "/" + key.getAllValues().get(index) + "/"
					+ value.getAllValues().get(index));
		}

		Assertions.assertThat(saved).containsExactlyInAnyOrder(
				"layout/" + SharedConstants.THEME_PREFERENCE_KEY + "/dark",
				OrganizationConstants.PAGE_KEY + "/" + OrganizationConstants.RECURSIVE + "/false",
				OrganizationConstants.PAGE_KEY + "/" + OrganizationConstants.ALLOW_CONFLICTS + "/false",
				OrganizationConstants.PAGE_KEY + "/" + OrganizationConstants.OVERWRITE_EXISTING + "/false");
	}

	@Test
	void settingsShouldListAndUpdateParameters() {
		ExtendedModelMap model = new ExtendedModelMap();
		var auth = new TestingAuthenticationToken("admin", "password");

		String view = controller.settings(model);
		String redirect = controller.update("nimbus-file-manager.api.max-page-size", "250", false, auth,
				new RedirectAttributesModelMap());

		Assertions.assertThat(view).isEqualTo("app/settings");
		Assertions.assertThat(redirect).isEqualTo("redirect:/app/settings");
		verify(settings).list();
		verify(settings).update("nimbus-file-manager.api.max-page-size", "250", "admin");
	}
}