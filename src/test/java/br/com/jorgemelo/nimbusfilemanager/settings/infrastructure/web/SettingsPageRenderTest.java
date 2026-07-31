package br.com.jorgemelo.nimbusfilemanager.settings.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.ui.Model;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateExclusionService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintActivityService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.InventoryWatchService;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.OfflineGeoDatasetStatus;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationProvider;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationRebuildScope;
import br.com.jorgemelo.nimbusfilemanager.geolocation.infrastructure.web.GeoDatasetSettingsModel;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MetadataRebuildAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.QuarantineFolderPolicy;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.settings.domain.model.AppSetting;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.LocaleConfig;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WebMvcConfig;

/**
 * Renders the settings page for real, template included. The controller unit
 * test only proves which view name comes back, so an expression that cannot be
 * evaluated - a fragment parameter referenced inside a SpEL selection, where the
 * root object becomes the element being tested - passed every check and still
 * broke the whole page at runtime.
 */
@WebMvcTest(controllers = SettingsWebController.class,
		excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
				classes = { WebMvcConfig.class, LocaleConfig.class }))
@AutoConfigureMockMvc(addFilters = false)
class SettingsPageRenderTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AppSettingService appSettingService;

	@MockitoBean
	private DuplicateExclusionService duplicateExclusionService;

	@MockitoBean
	private UserPagePreferenceService userPagePreferenceService;

	@MockitoBean
	private GeoDatasetSettingsModel geoDatasetSettingsModel;

	// Dependencies of the MVC interceptors and of the settings advices the slice
	// always loads, not of the controller under test. The metadata advice is kept
	// (not filtered out) because the page renders its panel too.
	@MockitoBean
	private AppUserRepository appUserRepository;

	@MockitoBean
	private MetadataRebuildAsyncRunner metadataRebuildAsyncRunner;

	@MockitoBean
	private InventoryRunningState inventoryRunningState;

	@MockitoBean
	private QuarantineFolderPolicy quarantineFolderPolicy;

	@MockitoBean
	private ExecutionQueryService executionQueryService;

	@MockitoBean
	private InventoryWatchService inventoryWatchService;

	@MockitoBean
	private FingerprintActivityService fingerprintActivityService;

	/**
	 * Stands in for the geo read model, which the page renders alongside the
	 * settings rows. Only what the template dereferences is filled in - the
	 * remaining attributes are read inside guards that treat null as absent.
	 */
	@BeforeEach
	void fillTheGeoModel() {
		// Nothing stored: every read answers the caller's own fallback, as an
		// unconfigured installation does.
		when(appSettingService.stringValue(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));

		doAnswer(invocation -> {
			Model model = invocation.getArgument(0);

			model.addAttribute("inventoryRunning", false);
			model.addAttribute("geoStatus", OfflineGeoDatasetStatus.unavailable("C:/geo", null));
			model.addAttribute("geoImportRunning", false);
			model.addAttribute("geoRebuildRunning", false);
			model.addAttribute("geoCacheSize", 0);
			model.addAttribute("geoResolvedCount", 0L);
			model.addAttribute("geoPendingCount", 0L);
			model.addAttribute("geoRebuildScopes", LocationRebuildScope.values());
			model.addAttribute("geoRebuildScope", LocationRebuildScope.PENDING.name());
			model.addAttribute("locationProviders", List.of(LocationProvider.ADMIN_BOUNDARIES));

			return null;
		}).when(geoDatasetSettingsModel).addTo(any(), any());
	}

	private AppSetting setting(String key, String value, String valueType) {
		return AppSetting.builder().settingKey(key).settingValue(value).valueType(valueType)
				.createdByUsername("system").build();
	}

	@Test
	void rendersTheProviderRowAsADropdownOfTheImplementedProviders() throws Exception {
		when(appSettingService.list()).thenReturn(List.of(
				setting(SettingsConstants.LOCATION_PROVIDER, LocationProvider.ADMIN_BOUNDARIES.name(), "STRING"),
				setting(SettingsConstants.API_MAX_PAGE_SIZE, "500", "INTEGER")));

		mockMvc.perform(get("/app/settings").with(csrf())).andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("name=\"value\"")));
	}

	/**
	 * A value no implemented provider matches survives as its own option instead of
	 * being silently rewritten to the first entry of the dropdown.
	 */
	@Test
	void keepsAStoredProviderThatNoStrategyImplements() throws Exception {
		when(appSettingService.list()).thenReturn(
				List.of(setting(SettingsConstants.LOCATION_PROVIDER, LocationProvider.GOOGLE_MAPS.name(), "STRING")));

		mockMvc.perform(get("/app/settings").with(csrf())).andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString(LocationProvider.GOOGLE_MAPS.name())));
	}
}