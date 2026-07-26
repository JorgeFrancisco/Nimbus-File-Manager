package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.InventoryPersistenceService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.facade.MetadataFacade;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;

class ConversionCatalogServiceTest {

	private final InventoryPersistenceService inventoryPersistenceService = mock(InventoryPersistenceService.class);
	private final MetadataFacade metadataFacade = mock(MetadataFacade.class);
	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final ConversionCatalogService service = new ConversionCatalogService(inventoryPersistenceService,
			metadataFacade, appSettingService);

	private final MetadataResult metadata = mock(MetadataResult.class);
	private final Path converted = Path.of("D:", "library", "2024", "clip.mp4");

	@Test
	void catalogsTheConvertedFileUnderTheConfiguredLibraryRoot() {
		when(appSettingService.stringValue(eq(SettingsConstants.WATCH_FOLDER), any())).thenReturn("D:\\library");
		when(metadataFacade.extract(eq(converted), any())).thenReturn(metadata);

		service.catalog(converted);

		verify(inventoryPersistenceService).save(eq(converted), eq(Path.of("D:", "library")), eq(metadata), any());
	}

	@Test
	void forcesTheAnalysisSoAStaleRowAtTheSamePathIsRewrittenNotCached() {
		when(appSettingService.stringValue(eq(SettingsConstants.WATCH_FOLDER), any())).thenReturn("D:\\library");
		when(appSettingService.booleanValue(SettingsConstants.WATCH_CALCULATE_HASHES, false)).thenReturn(true);
		when(metadataFacade.extract(eq(converted), any())).thenReturn(metadata);

		service.catalog(converted);

		ArgumentCaptor<MetadataOptions> options = ArgumentCaptor.forClass(MetadataOptions.class);

		verify(inventoryPersistenceService).save(any(), any(), any(), options.capture());

		Assertions.assertThat(options.getValue().forceAnalysis()).isTrue();
		Assertions.assertThat(options.getValue().calculateHashes()).isTrue();
	}

	@Test
	void fallsBackToTheContainingFolderWhileNoLibraryIsConfigured() {
		when(appSettingService.stringValue(eq(SettingsConstants.WATCH_FOLDER), any())).thenReturn("");
		when(metadataFacade.extract(eq(converted), any())).thenReturn(metadata);

		service.catalog(converted);

		verify(inventoryPersistenceService).save(eq(converted), eq(converted.getParent()), eq(metadata), any());
	}
}