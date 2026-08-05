package br.com.jorgemelo.nimbusfilemanager.settings.application;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.shared.application.catalog.CollectionCatalogMutations;

@ExtendWith(MockitoExtension.class)
class LibraryCatalogCleanupServiceTest {

	@TempDir
	Path tempDir;

	@Mock
	private CatalogFileRepository catalogFileRepository;

	@Mock
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Mock
	private WorkspaceManager workspaceManager;

	@Mock
	private EligibilityAnnouncer eligibilityAnnouncer;

	@Test
	void clearShouldDeleteCatalogRowsAndWipeThumbnailCacheContents() throws Exception {
		Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
		Path cache = Files.createDirectories(workspace.resolve("cache").resolve("thumbnails"));
		Path thumb = Files.writeString(cache.resolve("a.webp"), "x");
		Path nestedDir = Files.createDirectories(cache.resolve("2024"));
		Path nestedThumb = Files.writeString(nestedDir.resolve("b.webp"), "y");

		when(catalogFileRepository.deleteWithinLibrary(anyString(), anyString())).thenReturn(42);
		when(workspaceManager.getWorkspacePath()).thenReturn(workspace);
		when(workspaceManager.resolve("cache", "thumbnails")).thenReturn(cache);

		int deleted = service().clear("D:/library");

		Assertions.assertThat(deleted).isEqualTo(42);
		Assertions.assertThat(Files.exists(thumb)).isFalse();
		Assertions.assertThat(Files.exists(nestedThumb)).isFalse();
		Assertions.assertThat(Files.exists(nestedDir)).isFalse();
		// The cache directory itself is preserved; only its contents are wiped.
		Assertions.assertThat(Files.exists(cache)).isTrue();
	}

	@Test
	void clearShouldSkipCacheWipeWhenCacheIsOutsideWorkspace() {
		Path workspace = tempDir.resolve("workspace");

		when(catalogFileRepository.deleteWithinLibrary(anyString(), anyString())).thenReturn(0);
		when(workspaceManager.getWorkspacePath()).thenReturn(workspace);
		when(workspaceManager.resolve("cache", "thumbnails")).thenReturn(tempDir.resolve("outside"));

		// Cache path is not inside the workspace, so the wipe is skipped without error.
		Assertions.assertThat(service().clear("D:/library")).isZero();
	}

	/**
	 * The only routine in the application that empties a workspace subtree. What it
	 * is pointed at today is built from constants, but a cluster inside the tree
	 * has to stop it regardless - the catalog is the one thing under the workspace
	 * that no rebuild brings back.
	 */
	@Test
	void clearShouldRefuseToWipeATreeHoldingTheDatabaseCluster() throws Exception {
		Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
		Path cache = Files.createDirectories(workspace.resolve("cache").resolve("thumbnails"));
		Path cluster = Files.createDirectories(cache.resolve("cluster"));
		Path data = Files.writeString(cluster.resolve("PG_VERSION"), "17");

		when(catalogFileRepository.deleteWithinLibrary(anyString(), anyString())).thenReturn(7);
		when(workspaceManager.getWorkspacePath()).thenReturn(workspace);
		when(workspaceManager.resolve("cache", "thumbnails")).thenReturn(cache);

		Assertions.assertThat(service().clear("D:/library")).isEqualTo(7);

		Assertions.assertThat(Files.exists(data)).isTrue();
	}

	/**
	 * Forgetting a library takes active, fingerprinted files out of the catalog
	 * wholesale, so the answer a duplicate analysis published is about a collection
	 * that is no longer there. Said once for the switch.
	 */
	@Test
	void clearShouldAskForOneRegroupWhenRowsWereReallyForgotten() {
		when(catalogFileRepository.deleteWithinLibrary(anyString(), anyString())).thenReturn(7);
		when(workspaceManager.getWorkspacePath()).thenReturn(tempDir);
		when(workspaceManager.resolve("cache", "thumbnails")).thenReturn(tempDir.resolve("cache"));

		service().clear(tempDir.resolve("library").toString());

		verify(eligibilityAnnouncer).announce("library switch");
	}

	/** A root nothing was catalogued under leaves nothing to bring up to date. */
	@Test
	void clearShouldAskForNothingWhenNoRowWasForgotten() {
		when(catalogFileRepository.deleteWithinLibrary(anyString(), anyString())).thenReturn(0);
		when(workspaceManager.getWorkspacePath()).thenReturn(tempDir);
		when(workspaceManager.resolve("cache", "thumbnails")).thenReturn(tempDir.resolve("cache"));

		service().clear(tempDir.resolve("library").toString());

		verifyNoInteractions(eligibilityAnnouncer);
	}

	private LibraryCatalogCleanupService service() {
		return new LibraryCatalogCleanupService(
				new CollectionCatalogMutations(catalogFileRepository, catalogFileLocationRepository), workspaceManager,
				eligibilityAnnouncer);
	}
}