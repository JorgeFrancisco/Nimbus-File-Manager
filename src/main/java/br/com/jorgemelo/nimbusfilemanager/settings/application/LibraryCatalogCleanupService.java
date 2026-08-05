package br.com.jorgemelo.nimbusfilemanager.settings.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.database.application.ClusterProtection;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.shared.application.catalog.CatalogMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class LibraryCatalogCleanupService {

	private final CatalogMutations catalogMutations;
	private final WorkspaceManager workspaceManager;
	private final EligibilityAnnouncer eligibilityAnnouncer;

	public LibraryCatalogCleanupService(CatalogMutations catalogMutations, WorkspaceManager workspaceManager,
			EligibilityAnnouncer eligibilityAnnouncer) {
		this.catalogMutations = catalogMutations;
		this.workspaceManager = workspaceManager;
		this.eligibilityAnnouncer = eligibilityAnnouncer;
	}

	/**
	 * Forgetting a library removes catalogued files wholesale, active ones
	 * included, so whatever a published analysis was about is not what is there any
	 * more. Announced once for the whole switch and only when rows really went - a
	 * root nothing was catalogued under leaves nothing to bring up to date.
	 */
	@Transactional
	public int clear(String libraryPath) {
		int deleted = catalogMutations.forgetLibrary(libraryPath);

		clearThumbnailCache();

		if (deleted > 0) {
			eligibilityAnnouncer.announce("library switch");
		}

		return deleted;
	}

	private void clearThumbnailCache() {
		Path cache = workspaceManager.resolve("cache", "thumbnails");

		Path workspace = workspaceManager.getWorkspacePath().toAbsolutePath().normalize();

		Path target = cache.toAbsolutePath().normalize();

		if (!target.startsWith(workspace) || target.equals(workspace) || !Files.exists(target)) {
			return;
		}

		// The path above is built from constants, so today it cannot be the cluster.
		// It is checked anyway because this is the only routine in the application
		// that empties a workspace subtree: whatever moves the cache, renames a
		// folder or reuses this method later inherits the guard instead of having to
		// remember it.
		if (ClusterProtection.holdsCluster(target)) {
			log.warn("Refusing to clear {}: it holds the embedded database cluster", target);

			return;
		}

		try (var paths = Files.walk(target)) {
			paths.sorted(Comparator.reverseOrder()).filter(path -> !path.equals(target)).forEach(this::deleteQuietly);
		} catch (IOException exception) {
			log.warn("Could not fully clear thumbnail cache after library switch", exception);
		}
	}

	private void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException exception) {
			log.debug("Could not delete obsolete thumbnail cache entry {}", path, exception);
		}
	}
}