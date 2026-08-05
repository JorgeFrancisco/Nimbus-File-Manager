package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Renaming one file leaves two rows describing where it is, and both have to
 * move. Updating only the catalog entry is what reconciliation calls a stale
 * path: the screens read the placement, so the file would show up as missing
 * next to an unregistered copy of itself until a pass repaired it.
 */
class ExplorerRenamePersistenceTest {

	private static final Instant NOW = Instant.parse("2026-08-06T10:00:00Z");

	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);
	private final CatalogFileLocationRepository catalogFileLocationRepository = mock(
			CatalogFileLocationRepository.class);

	private final ExplorerRenamePersistence persistence = new ExplorerRenamePersistence(catalogFileRepository,
			catalogFileLocationRepository, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void pointsBothTheEntryAndItsPlacementAtTheNewName(@TempDir Path folder) {
		Path source = folder.resolve("photo.jpg");
		Path target = folder.resolve("holiday.jpeg");

		CatalogFile stored = CatalogFile.builder().fileKey(PathUtils.normalize(source)).fileName("photo.jpg")
				.extension("jpg").build();

		CatalogFileLocation location = CatalogFileLocation.builder().catalogFile(stored)
				.currentPath(PathUtils.normalize(source)).currentFolder(PathUtils.normalize(folder)).build();

		stored.setLocation(location);

		when(catalogFileRepository.findByFileKey(PathUtils.normalize(source))).thenReturn(Optional.of(stored));

		assertThat(persistence.rename(source, target)).isTrue();
		assertThat(stored.getFileKey()).isEqualTo(PathUtils.normalize(target));
		assertThat(stored.getFileName()).isEqualTo("holiday.jpeg");
		assertThat(stored.getExtension()).isEqualTo("jpeg");
		assertThat(location.getCurrentPath()).isEqualTo(PathUtils.normalize(target));
		assertThat(location.getUpdatedAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));

		verify(catalogFileRepository).save(stored);
		verify(catalogFileLocationRepository).save(location);
	}

	/**
	 * A file the catalog never saw is still renamed on disk; there is simply no row
	 * to repoint, and inventing one would be inventing history.
	 */
	@Test
	void writesNothingForAFileTheCatalogDoesNotKnow(@TempDir Path folder) {
		when(catalogFileRepository.findByFileKey(any())).thenReturn(Optional.empty());

		assertThat(persistence.rename(folder.resolve("photo.jpg"), folder.resolve("holiday.jpg"))).isFalse();

		verify(catalogFileRepository, never()).save(any());
		verify(catalogFileLocationRepository, never()).save(any());
	}
}