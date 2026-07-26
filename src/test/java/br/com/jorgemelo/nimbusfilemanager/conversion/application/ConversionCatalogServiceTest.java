package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

	private Path library;
	private Path converted;

	@BeforeEach
	void setUp(@TempDir Path root) {
		// An absolute library root, because the service resolves the configured folder
		// against the working directory: a relative one would render differently on
		// Windows and on the Linux CI.
		library = root.resolve("library");
		converted = library.resolve("2024").resolve("clip.mp4");
	}

	@Test
	void catalogsTheConvertedFileUnderTheConfiguredLibraryRoot() {
		when(appSettingService.stringValue(eq(SettingsConstants.WATCH_FOLDER), any())).thenReturn(library.toString());
		when(metadataFacade.extract(eq(converted), any())).thenReturn(metadata);

		service.catalog(converted);

		verify(inventoryPersistenceService).save(eq(converted), eq(library), eq(metadata), any());
	}

	@Test
	void forcesTheAnalysisSoAStaleRowAtTheSamePathIsRewrittenNotCached() {
		when(appSettingService.stringValue(eq(SettingsConstants.WATCH_FOLDER), any())).thenReturn(library.toString());
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