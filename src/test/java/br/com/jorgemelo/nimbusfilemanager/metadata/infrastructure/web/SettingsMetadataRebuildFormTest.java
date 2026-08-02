package br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MetadataRebuildAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataRebuildPreferences;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.LocaleConfig;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WebMvcConfig;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web.AppViewModelAdvice;

/**
 * Binding of the settings form onto {@link MetadataRebuildRequest}, which unit
 * construction cannot prove: the record has no default constructor, the field
 * checkboxes arrive as a repeated parameter and the unticked "simulate" box
 * arrives as no parameter at all - each one a way the panel could silently
 * rebuild the wrong thing (or nothing).
 */
@WebMvcTest(controllers = SettingsMetadataWebController.class,
		excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
				classes = { WebMvcConfig.class, LocaleConfig.class, AppViewModelAdvice.class,
						MetadataRebuildSettingsAdvice.class }))
@AutoConfigureMockMvc(addFilters = false)
class SettingsMetadataRebuildFormTest {

	@Autowired
	private MockMvc mockMvc;

	// Wired into every web slice by WebMvcConfig: the interceptor that holds the
	// screens off while a restore is replacing the catalog.
	@MockitoBean
	private CatalogBackupAsyncRunner catalogBackupAsyncRunner;

	@MockitoBean
	private BackgroundWorkGate backgroundWorkGate;

	@MockitoBean
	private MetadataRebuildAsyncRunner metadataRebuildAsyncRunner;

	@MockitoBean
	private UserPagePreferenceService userPagePreferenceService;

	@MockitoBean
	private InventoryRunningState inventoryRunningState;

	// Dependencies of the MVC interceptors the slice always loads, not of the
	// controller under test.
	@MockitoBean
	private AppUserRepository appUserRepository;

	@MockitoBean
	private AppSettingService appSettingService;

	/**
	 * A real fixed clock rather than a mock: the controller stamps the run with
	 * {@code LocalDateTime.now(clock)}, which a bare mock answers null for.
	 */
	@TestBean
	private Clock clock;

	static Clock clock() {
		return Clock.fixed(Instant.parse("2026-07-26T14:00:00Z"), ZoneOffset.UTC);
	}

	@Test
	void bindsTheFolderTheTickedFieldsAndTheSimulateBox() throws Exception {
		when(metadataRebuildAsyncRunner.start(any())).thenReturn(true);

		mockMvc.perform(post("/app/settings/metadata/rebuild").param("sourcePath", "D:\\photos")
				.param("refresh", "SUBCATEGORY").param("refresh", "DATE").param("dryRun", "true"))
				.andExpect(status().is3xxRedirection());

		ArgumentCaptor<MetadataRebuildRequest> request = ArgumentCaptor.forClass(MetadataRebuildRequest.class);

		verify(metadataRebuildAsyncRunner).rebuild(request.capture());

		Assertions.assertThat(request.getValue().sourcePath()).isEqualTo("D:\\photos");
		Assertions.assertThat(request.getValue().refresh()).containsExactly(MetadataRebuildField.SUBCATEGORY,
				MetadataRebuildField.DATE);
		Assertions.assertThat(request.getValue().dryRun()).isTrue();
	}

	/**
	 * An unticked checkbox sends nothing, and {@code dryRun} is a primitive: the
	 * missing parameter has to land as {@code false} instead of failing the bind.
	 */
	@Test
	void treatsTheUntickedSimulateBoxAsARealRun() throws Exception {
		when(metadataRebuildAsyncRunner.start(any())).thenReturn(true);

		mockMvc.perform(
				post("/app/settings/metadata/rebuild").param("sourcePath", "D:\\photos").param("refresh", "DATE"))
				.andExpect(status().is3xxRedirection());

		ArgumentCaptor<MetadataRebuildRequest> request = ArgumentCaptor.forClass(MetadataRebuildRequest.class);

		verify(metadataRebuildAsyncRunner).rebuild(request.capture());

		Assertions.assertThat(request.getValue().dryRun()).isFalse();
	}

	/**
	 * Picking a folder and leaving the screen must not discard it, so the panel
	 * stores the choices as they change instead of only when a rebuild starts.
	 */
	@Test
	void storesTheChoicesWithoutStartingARebuild() throws Exception {
		mockMvc.perform(
				post("/app/settings/metadata/preferences").param("sourcePath", "D:\\").param("refresh", "SUBCATEGORY"))
				.andExpect(status().isOk());

		verify(userPagePreferenceService).save("system", MetadataRebuildPreferences.PAGE_KEY,
				MetadataRebuildPreferences.SOURCE_PATH_KEY, "D:\\");
		verify(userPagePreferenceService).save("system", MetadataRebuildPreferences.PAGE_KEY,
				MetadataRebuildPreferences.FIELDS_KEY, "SUBCATEGORY");

		verify(metadataRebuildAsyncRunner, never()).start(any());
		verify(metadataRebuildAsyncRunner, never()).rebuild(any());
	}

	@Test
	void persistsTheChoicesSoThePanelReopensOnThem() throws Exception {
		when(metadataRebuildAsyncRunner.start(any())).thenReturn(true);

		mockMvc.perform(post("/app/settings/metadata/rebuild").param("sourcePath", "D:\\photos").param("refresh", "GPS")
				.param("refresh", "CAMERA")).andExpect(status().is3xxRedirection());

		verify(userPagePreferenceService).save("system", MetadataRebuildPreferences.PAGE_KEY,
				MetadataRebuildPreferences.SOURCE_PATH_KEY, "D:\\photos");
		verify(userPagePreferenceService).save("system", MetadataRebuildPreferences.PAGE_KEY,
				MetadataRebuildPreferences.FIELDS_KEY, "GPS,CAMERA");
		verify(userPagePreferenceService).save("system", MetadataRebuildPreferences.PAGE_KEY,
				MetadataRebuildPreferences.DRY_RUN_KEY, "false");
	}
}