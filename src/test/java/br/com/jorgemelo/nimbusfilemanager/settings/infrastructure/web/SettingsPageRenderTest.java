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

import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;
import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.BackupSnapshot;
import br.com.jorgemelo.nimbusfilemanager.backup.domain.enums.BackupPhase;
import br.com.jorgemelo.nimbusfilemanager.backup.infrastructure.web.BackupSettingsModel;
import br.com.jorgemelo.nimbusfilemanager.database.application.dto.EmbeddedDatabaseStatus;
import br.com.jorgemelo.nimbusfilemanager.database.infrastructure.web.EmbeddedDatabaseSettingsModel;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateExclusionService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintActivityService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.InventoryWatchService;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.OfflineGeoDatasetStatus;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationProvider;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationRebuildScope;
import br.com.jorgemelo.nimbusfilemanager.geolocation.infrastructure.web.GeoDatasetSettingsModel;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MetadataRebuildLauncher;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MetadataRunReader;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateCheckService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.QuarantineFolderPolicy;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.ExternalToolStatus;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.ToolInstallSnapshot;
import br.com.jorgemelo.nimbusfilemanager.settings.domain.enums.ToolInstallPhase;
import br.com.jorgemelo.nimbusfilemanager.settings.domain.model.AppSetting;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.LocaleConfig;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WebMvcConfig;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.UpdateInstallSnapshot;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.UpdateStatus;
import br.com.jorgemelo.nimbusfilemanager.update.domain.enums.UpdatePhase;
import br.com.jorgemelo.nimbusfilemanager.update.infrastructure.web.UpdateSettingsModel;

/**
 * Renders the settings page for real, template included. The controller unit
 * test only proves which view name comes back, so an expression that cannot be
 * evaluated - a fragment parameter referenced inside a SpEL selection, where
 * the root object becomes the element being tested - passed every check and
 * still broke the whole page at runtime.
 */
@WebMvcTest(controllers = SettingsWebController.class,
		excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
				classes = { WebMvcConfig.class, LocaleConfig.class }))
@AutoConfigureMockMvc(addFilters = false)
class SettingsPageRenderTest {

	private static final ExternalToolStatus MISSING_TOOLS = new ExternalToolStatus(false, "ffmpeg", false, "ffprobe",
			null, false, true, "C:/app/tools/ffmpeg/bin");

	@Autowired
	private MockMvc mockMvc;

	// Wired into every web slice by WebMvcConfig: the interceptor that holds the
	// screens off while a restore is replacing the catalog.
	@MockitoBean
	private CatalogBackupAsyncRunner catalogBackupAsyncRunner;

	@MockitoBean
	private BackgroundWorkGate backgroundWorkGate;

	@MockitoBean
	private AppSettingService appSettingService;

	@MockitoBean
	private DuplicateExclusionService duplicateExclusionService;

	@MockitoBean
	private UserPagePreferenceService userPagePreferenceService;

	@MockitoBean
	private GeoDatasetSettingsModel geoDatasetSettingsModel;

	@MockitoBean
	private ExternalToolSettingsModel externalToolSettingsModel;

	@MockitoBean
	private BackupSettingsModel backupSettingsModel;

	@MockitoBean
	private EmbeddedDatabaseSettingsModel embeddedDatabaseSettingsModel;

	@MockitoBean
	private UpdateSettingsModel updateSettingsModel;

	// Dependencies of the MVC interceptors and of the settings advices the slice
	// always loads, not of the controller under test. The metadata advice is kept
	// (not filtered out) because the page renders its panel too.
	@MockitoBean
	private UpdateCheckService updateCheckService;

	@MockitoBean
	private AppUserRepository appUserRepository;

	@MockitoBean
	private MetadataRebuildLauncher metadataRebuildLauncher;

	@MockitoBean
	private MetadataRunReader metadataRunReader;

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

		doAnswer(invocation -> {
			Model model = invocation.getArgument(0);

			model.addAttribute("toolStatus", MISSING_TOOLS);
			model.addAttribute("toolInstallRunning", false);
			model.addAttribute("toolInstallProgress", new ToolInstallSnapshot(ToolInstallPhase.IDLE, 0, -1, -1, -1));

			return null;
		}).when(externalToolSettingsModel).addTo(any(), any());

		doAnswer(invocation -> {
			Model model = invocation.getArgument(0);

			model.addAttribute("backups", List.of());
			model.addAttribute("backupFolder", "C:/app/workspace/backup");
			model.addAttribute("backupRunning", false);
			model.addAttribute("backupProgress", new BackupSnapshot(BackupPhase.IDLE, 0));

			return null;
		}).when(backupSettingsModel).addTo(any(), any());

		doAnswer(invocation -> {
			Model model = invocation.getArgument(0);

			model.addAttribute("embeddedDatabaseStatus",
					new EmbeddedDatabaseStatus(false, false, null, "C:/app/tools/postgresql", true, false));

			return null;
		}).when(embeddedDatabaseSettingsModel).addTo(any(), any());

		// A packaged run that has not found anything: the section renders its version
		// and the check, which is the state every installation is in most of the time.
		doAnswer(invocation -> {
			Model model = invocation.getArgument(0);

			model.addAttribute("updateStatus", new UpdateStatus("6.0.0.147", false, null, null, true, false, null));
			model.addAttribute("updateProgress",
					new UpdateInstallSnapshot(UpdatePhase.IDLE, false, 0, -1, -1, -1, null));

			return null;
		}).when(updateSettingsModel).addTo(any(), any());
	}

	private AppSetting setting(String key, String value, String valueType) {
		return AppSetting.builder().settingKey(key).settingValue(value).valueType(valueType).createdByUsername("system")
				.build();
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
	 * The time zone and the map settings belong to no key-prefix group until a
	 * panel claims them, and an unclaimed setting is stored, read by the
	 * application and never rendered. This keeps both panels on screen - and with
	 * them the only render of the time-zone dropdown, whose matching expression had
	 * the same latent defect that broke the provider row.
	 */
	@Test
	void rendersTheSettingsThatBelongToNoOtherGroup() throws Exception {
		when(appSettingService.list())
				.thenReturn(List.of(setting(SettingsConstants.TIMEZONE, "America/Sao_Paulo", "ZONE_ID"),
						setting(SettingsConstants.MAP_MAX_ZOOM, "19", "INTEGER")));

		mockMvc.perform(get("/app/settings").with(csrf())).andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("America/Sao_Paulo")))
				.andExpect(content().string(Matchers.containsString("nimbus-file-manager.map.max-zoom")));
	}

	/**
	 * A stored time zone the offered list does not carry stays selectable instead
	 * of being dropped from the dropdown that is about to save over it.
	 */
	@Test
	void keepsAStoredTimeZoneThatIsNotOffered() throws Exception {
		when(appSettingService.list())
				.thenReturn(List.of(setting(SettingsConstants.TIMEZONE, "Pacific/Kiritimati", "ZONE_ID")));

		mockMvc.perform(get("/app/settings").with(csrf())).andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("Pacific/Kiritimati")));
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

	/**
	 * The external-tools panel offers the install action when nothing is found,
	 * which is the whole point of the section: a fresh installation must not send
	 * the operator to the README to fetch ffmpeg by hand.
	 */
	@Test
	void offersTheToolInstallActionWhenNothingIsInstalled() throws Exception {
		when(appSettingService.list()).thenReturn(List.of());

		mockMvc.perform(get("/app/settings").with(csrf())).andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("/app/settings/tools/install")))
				.andExpect(content().string(Matchers.containsString("C:/app/tools/ffmpeg/bin")));
	}
}