package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Month;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.InventoryPersistenceService;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryPersistenceResult;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.InventoryPersistenceAction;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.ProcessResult;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.ResolvedMediaDate;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.facade.MetadataFacade;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;

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

		// The ordinary answer: a converted file the catalog had never heard of. The
		// tests below are about where and with what it is catalogued, so they all get
		// this and only the revival test says otherwise.
		when(inventoryPersistenceService.save(any(), any(), any(), any()))
				.thenReturn(new InventoryPersistenceResult(ProcessResult.ANALYZED, InventoryPersistenceAction.CREATED));
	}

	/**
	 * The one thing this hands back, and the reason it hands anything back: a
	 * converted file can land on a path the catalog knew and had marked missing -
	 * the output of a previous conversion, removed from outside the application -
	 * and cataloguing it brings that entry back to life. The batch reads this to
	 * decide, once, whether the set a duplicate analysis looks at has changed.
	 */
	@Test
	void saysWhenCataloguingBroughtAMissingEntryBackToLife() {
		when(appSettingService.stringValue(eq(SettingsConstants.WATCH_FOLDER), any())).thenReturn(library.toString());
		when(metadataFacade.extract(eq(converted), any())).thenReturn(metadata);

		Assertions.assertThat(service.catalog(converted, null)).as("an entry the catalog had never lost").isFalse();

		when(inventoryPersistenceService.save(any(), any(), any(), any())).thenReturn(
				new InventoryPersistenceResult(ProcessResult.ANALYZED, InventoryPersistenceAction.REACTIVATED));

		Assertions.assertThat(service.catalog(converted, null)).isTrue();
	}

	@Test
	void catalogsTheConvertedFileUnderTheConfiguredLibraryRoot() {
		when(appSettingService.stringValue(eq(SettingsConstants.WATCH_FOLDER), any())).thenReturn(library.toString());
		when(metadataFacade.extract(eq(converted), any())).thenReturn(metadata);

		service.catalog(converted, null);

		verify(inventoryPersistenceService).save(eq(converted), eq(library), eq(metadata), any());
	}

	@Test
	void forcesTheAnalysisSoAStaleRowAtTheSamePathIsRewrittenNotCached() {
		when(appSettingService.stringValue(eq(SettingsConstants.WATCH_FOLDER), any())).thenReturn(library.toString());
		when(appSettingService.booleanValue(SettingsConstants.WATCH_CALCULATE_HASHES, false)).thenReturn(true);
		when(metadataFacade.extract(eq(converted), any())).thenReturn(metadata);

		service.catalog(converted, null);

		ArgumentCaptor<MetadataOptions> options = ArgumentCaptor.forClass(MetadataOptions.class);

		verify(inventoryPersistenceService).save(any(), any(), any(), options.capture());

		Assertions.assertThat(options.getValue().forceAnalysis()).isTrue();
		Assertions.assertThat(options.getValue().calculateHashes()).isTrue();
	}

	@Test
	void fallsBackToTheContainingFolderWhileNoLibraryIsConfigured() {
		when(appSettingService.stringValue(eq(SettingsConstants.WATCH_FOLDER), any())).thenReturn("");
		when(metadataFacade.extract(eq(converted), any())).thenReturn(metadata);

		service.catalog(converted, null);

		verify(inventoryPersistenceService).save(eq(converted), eq(converted.getParent()), eq(metadata), any());
	}

	/**
	 * A converted file is written now, so a source with no embedded or name date
	 * would be re-dated to the conversion instant and jump to the top of the
	 * timeline. The date the replaced file had wins over that.
	 */
	@Test
	void keepsTheDateOfTheFileItReplacesWhenTheNewOneOnlyHasAFilesystemTimestamp() {
		LocalDateTime original = LocalDateTime.of(2011, Month.MARCH, 4, 18, 20);

		MetadataResult extracted = MetadataResult.builder().fileName("clip.mp4")
				.captureDate(LocalDateTime.of(2026, Month.JULY, 28, 15, 48)).dateSource(DateSource.FILE_MODIFIED_AT)
				.build();

		when(appSettingService.stringValue(eq(SettingsConstants.WATCH_FOLDER), any())).thenReturn(library.toString());
		when(metadataFacade.extract(eq(converted), any())).thenReturn(extracted);

		service.catalog(converted, new ResolvedMediaDate(original, DateSource.MEDIA_INFO));

		ArgumentCaptor<MetadataResult> saved = ArgumentCaptor.forClass(MetadataResult.class);

		verify(inventoryPersistenceService).save(any(), any(), saved.capture(), any());

		Assertions.assertThat(saved.getValue().getCaptureDate()).isEqualTo(original);
		Assertions.assertThat(saved.getValue().getDateSource()).isEqualTo(DateSource.MEDIA_INFO);
	}

	/**
	 * The other way round the extraction wins: the converted file kept its embedded
	 * date, which is more trustworthy than whatever the old row had settled for.
	 */
	@Test
	void keepsTheExtractedDateWhenItIsAtLeastAsTrustworthyAsTheOldOne() {
		LocalDateTime embedded = LocalDateTime.of(2011, Month.MARCH, 4, 18, 20);

		MetadataResult extracted = MetadataResult.builder().fileName("clip.mp4").captureDate(embedded)
				.dateSource(DateSource.MEDIA_INFO).build();

		when(appSettingService.stringValue(eq(SettingsConstants.WATCH_FOLDER), any())).thenReturn(library.toString());
		when(metadataFacade.extract(eq(converted), any())).thenReturn(extracted);

		service.catalog(converted,
				new ResolvedMediaDate(LocalDateTime.of(2020, Month.JANUARY, 1, 0, 0), DateSource.FILE_NAME));

		ArgumentCaptor<MetadataResult> saved = ArgumentCaptor.forClass(MetadataResult.class);

		verify(inventoryPersistenceService).save(any(), any(), saved.capture(), any());

		Assertions.assertThat(saved.getValue().getCaptureDate()).isEqualTo(embedded);
		Assertions.assertThat(saved.getValue().getDateSource()).isEqualTo(DateSource.MEDIA_INFO);
	}

	/** A source row with no date of its own has nothing to lend. */
	@Test
	void aSourceWithoutADateLeavesTheExtractionAlone() {
		MetadataResult extracted = MetadataResult.builder().fileName("clip.mp4")
				.captureDate(LocalDateTime.of(2026, Month.JULY, 28, 15, 48)).dateSource(DateSource.FILE_MODIFIED_AT)
				.build();

		when(appSettingService.stringValue(eq(SettingsConstants.WATCH_FOLDER), any())).thenReturn(library.toString());
		when(metadataFacade.extract(eq(converted), any())).thenReturn(extracted);

		service.catalog(converted, new ResolvedMediaDate(null, DateSource.UNKNOWN));

		verify(inventoryPersistenceService).save(any(), any(), eq(extracted), any());
	}
}