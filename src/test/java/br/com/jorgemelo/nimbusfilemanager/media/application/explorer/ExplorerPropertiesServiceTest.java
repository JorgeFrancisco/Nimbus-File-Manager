package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import br.com.jorgemelo.nimbusfilemanager.media.application.dto.ExplorerItemProperties;
import br.com.jorgemelo.nimbusfilemanager.media.domain.repository.projection.FolderInventorySummary;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * A file is measured from disk, a folder from the catalog. The second choice is
 * the interesting one: it keeps the dialog instant on a folder of any size, and
 * these checks pin what that costs - the folder numbers describe what was
 * inventoried, and the folder itself never counts as one of its own subfolders.
 */
class ExplorerPropertiesServiceTest {

	private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);
	private final CatalogFileLocationRepository catalogFileLocationRepository = mock(
			CatalogFileLocationRepository.class);

	private ExplorerPropertiesService service() {
		ExplorerPropertiesService service = new ExplorerPropertiesService(catalogFileRepository,
				catalogFileLocationRepository);

		ResourceBundleMessageSource messages = new ResourceBundleMessageSource();

		messages.setBasename("messages");
		messages.setDefaultEncoding("UTF-8");
		messages.setFallbackToSystemLocale(false);

		service.setMessageSource(messages);

		return service;
	}

	private FolderInventorySummary summary(long files, long folders, Long bytes) {
		FolderInventorySummary summary = mock(FolderInventorySummary.class);

		when(summary.getFileCount()).thenReturn(files);
		when(summary.getFolderCount()).thenReturn(folders);
		when(summary.getSizeBytes()).thenReturn(bytes);

		return summary;
	}

	/**
	 * The component under test resolves through LocaleContextHolder, so without
	 * pinning the language these assertions would compare pt-BR text against
	 * whatever the machine defaults to - green here and red on an English CI
	 * runner, which is exactly what happened.
	 */
	@BeforeEach
	void useThePortugueseBundle() {
		LocaleContextHolder.setLocale(PT_BR);
	}

	@AfterEach
	void releaseTheLocale() {
		LocaleContextHolder.resetLocaleContext();
	}

	@Test
	void describesAFileFromDiskAndSaysWhetherItIsCataloged(@TempDir Path folder) throws IOException {
		Path file = Files.write(folder.resolve("photo.jpg"), new byte[2048]);

		when(catalogFileLocationRepository.findPresentByPath(PathUtils.normalize(file), PathFlavor.of(file).name()))
				.thenReturn(Optional.of(CatalogFile.builder().build()));

		ExplorerItemProperties properties = service().of(file);

		Assertions.assertThat(properties.directory()).isFalse();
		Assertions.assertThat(properties.name()).isEqualTo("photo.jpg");
		Assertions.assertThat(properties.sizeBytes()).isEqualTo(2048);
		Assertions.assertThat(properties.sizeLabel()).isEqualTo("2.00 KB");
		Assertions.assertThat(properties.typeLabel()).isEqualTo("JPG");
		Assertions.assertThat(properties.cataloged()).isTrue();
		Assertions.assertThat(properties.fileCount()).isNull();
	}

	@Test
	void marksAFileTheCatalogNeverSaw(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("photo.jpg"));

		when(catalogFileLocationRepository.findPresentByPath(any(), any())).thenReturn(Optional.empty());

		Assertions.assertThat(service().of(file).cataloged()).isFalse();
	}

	/**
	 * A filesystem root has neither a name nor a parent, so both fall back instead
	 * of dereferencing null: the dialog names it by its own path and leaves the
	 * location empty. Reached through getRoot() so the test means the same thing on
	 * Windows and on the Linux runner.
	 */
	@Test
	void describesAFilesystemRoot(@TempDir Path folder) throws IOException {
		FolderInventorySummary summary = summary(0, 0, 0L);

		when(catalogFileRepository.summarizeFolder(any(), any())).thenReturn(summary);

		ExplorerItemProperties properties = service().of(folder.getRoot());

		Assertions.assertThat(properties.name()).isNotBlank();
		Assertions.assertThat(properties.parentPath()).isNull();
	}

	/**
	 * With no extension there is nothing to label the type with, so the dialog
	 * falls back to a generic word rather than leaving the cell empty.
	 */
	@Test
	void labelsAFileWithoutAnExtension(@TempDir Path folder) throws IOException {
		Path file = Files.createFile(folder.resolve("README"));

		when(catalogFileLocationRepository.findPresentByPath(any(), any())).thenReturn(Optional.empty());

		Assertions.assertThat(service().of(file).typeLabel()).isEqualTo("Arquivo");
	}

	/**
	 * The query groups by folder, and the folder being described is one of them, so
	 * the dialog would claim one subfolder for a flat folder if the count were used
	 * as it comes.
	 */
	@Test
	void reportsFolderTotalsFromTheCatalogWithoutCountingItself(@TempDir Path folder) throws IOException {
		FolderInventorySummary summary = summary(12, 3, 5_242_880L);

		when(catalogFileRepository.summarizeFolder(any(), any())).thenReturn(summary);

		ExplorerItemProperties properties = service().of(folder);

		Assertions.assertThat(properties.directory()).isTrue();
		Assertions.assertThat(properties.fileCount()).isEqualTo(12);
		Assertions.assertThat(properties.folderCount()).isEqualTo(2);
		Assertions.assertThat(properties.sizeLabel()).isEqualTo("5.00 MB");
	}

	/**
	 * Nothing inventoried under the folder leaves the sum null, which must read as
	 * zero bytes rather than blowing up the dialog.
	 */
	@Test
	void treatsAnEmptyInventoryAsZeroBytes(@TempDir Path folder) throws IOException {
		FolderInventorySummary summary = summary(0, 0, null);

		when(catalogFileRepository.summarizeFolder(any(), any())).thenReturn(summary);

		ExplorerItemProperties properties = service().of(folder);

		Assertions.assertThat(properties.sizeBytes()).isZero();
		Assertions.assertThat(properties.folderCount()).isZero();
	}
}