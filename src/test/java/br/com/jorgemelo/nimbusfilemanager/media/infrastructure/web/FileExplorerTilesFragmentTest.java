package br.com.jorgemelo.nimbusfilemanager.media.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.FileExplorerEntry;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.FileExplorerView;
import br.com.jorgemelo.nimbusfilemanager.media.application.explorer.FileExplorerService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MetadataRebuildAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.LocaleConfig;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WebMvcConfig;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web.AppViewModelAdvice;

/**
 * Infinite scroll renders {@code app/files :: tiles} on its own, with no page
 * around it. Anything the tiles depend on has to live inside the fragment
 * element: a {@code th:with} on an ancestor is simply not evaluated in a
 * partial render, and the thumbnail width silently became {@code null} - every
 * appended page asked for {@code ?w=null} and got a 500 instead of an image.
 */
@WebMvcTest(controllers = FileExplorerWebController.class,
		excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
				classes = { WebMvcConfig.class, LocaleConfig.class, AppViewModelAdvice.class }))
@AutoConfigureMockMvc(addFilters = false)
class FileExplorerTilesFragmentTest {

	@Autowired
	private MockMvc mockMvc;

	// Wired into every web slice by WebMvcConfig: the interceptor that holds the
	// screens off while a restore is replacing the catalog.
	@MockitoBean
	private CatalogBackupAsyncRunner catalogBackupAsyncRunner;

	@MockitoBean
	private BackgroundWorkGate backgroundWorkGate;

	@MockitoBean
	private FileExplorerService fileExplorerService;

	@MockitoBean
	private UserPagePreferenceService userPagePreferenceService;

	@MockitoBean
	private ScanExclusionService scanExclusionService;

	// Dependency of the MVC interceptors the slice always loads, not of the
	// controller under test.
	@MockitoBean
	private AppUserRepository appUserRepository;

	@MockitoBean
	private AppSettingService appSettingService;

	@MockitoBean
	private MetadataRebuildAsyncRunner metadataRebuildAsyncRunner;

	@MockitoBean
	private InventoryRunningState inventoryRunningState;

	private FileExplorerView view(String viewMode) {
		FileExplorerEntry photo = new FileExplorerEntry("photo.png", "D:\\photos\\photo.png", false, false, true,
				"PHOTO", true, false, false, false, false, null, 1024L, null, "-", 7L,
				UUID.fromString("019f7bc4-b73d-7594-b7a3-6e5c0d71f18f"));

		return new FileExplorerView("D:\\photos", "D:\\", viewMode, true, true, false, 0, 1, 0, 1, 1, 20, 21, 2, true,
				false, List.of(photo));
	}

	@Test
	void appendedTilesCarryTheThumbnailWidthOfTheViewMode() throws Exception {
		when(fileExplorerService.browse(any(), any(), any(), any())).thenReturn(view("large"));

		mockMvc.perform(get("/app/files/items").param("path", "D:\\photos").param("view", "large").param("page", "1")
				.with(csrf())).andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("/thumbnail?w=640")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("w=null"))));
	}

	@Test
	void appendedTilesFallBackToTheSmallWidthOnTheCompactGrid() throws Exception {
		when(fileExplorerService.browse(any(), any(), any(), any())).thenReturn(view("grid"));

		mockMvc.perform(get("/app/files/items").param("path", "D:\\photos").param("view", "grid").param("page", "1")
				.with(csrf())).andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("/thumbnail?w=320")));
	}
}