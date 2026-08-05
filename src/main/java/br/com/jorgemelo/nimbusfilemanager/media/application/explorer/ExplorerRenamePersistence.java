package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ExtensionUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Catalog side of renaming one file, in a transaction of its own.
 *
 * <p>
 * A separate bean for the reason every other persistence step in this product
 * is one: Spring's {@code @Transactional} does not apply to self-invocation, so
 * the service that moved the file has to cross a bean boundary for a
 * transaction to open at all - and the move itself must stay outside it, since
 * a file system is not something a rollback can reach.
 *
 * <p>
 * Both rows or neither. The entry and its placement are two tables saying where
 * the same file is, and leaving one behind is precisely the disagreement
 * reconciliation calls a stale path: the catalog pointing at a real file, the
 * placement pointing at a name that no longer exists, and the screens - which
 * read the placement - showing the file as missing next to an unregistered copy
 * of itself.
 */
@Component
public class ExplorerRenamePersistence {

	private final CatalogFileRepository catalogFileRepository;
	private final CatalogFileLocationRepository catalogFileLocationRepository;
	private final Clock clock;

	public ExplorerRenamePersistence(CatalogFileRepository catalogFileRepository,
			CatalogFileLocationRepository catalogFileLocationRepository, Clock clock) {
		this.catalogFileRepository = catalogFileRepository;
		this.catalogFileLocationRepository = catalogFileLocationRepository;
		this.clock = clock;
	}

	/**
	 * Points the catalogued file at its new name. A file the catalog never knew
	 * about is renamed on disk and nothing is written here - there is no row to
	 * correct, and inventing one would be inventing history.
	 *
	 * @return whether a catalogued file was repointed
	 */
	@Transactional
	public boolean rename(Path source, Path target) {
		Optional<CatalogFile> stored = catalogFileRepository.findByFileKey(PathUtils.normalize(source));

		if (stored.isEmpty()) {
			return false;
		}

		CatalogFile file = stored.get();

		file.setFileKey(PathUtils.normalize(target));
		file.setFileName(target.getFileName().toString());
		file.setExtension(ExtensionUtils.fromPath(target));

		catalogFileRepository.save(file);

		CatalogFileLocation location = file.getLocation();

		if (location != null) {
			location.setCurrentPath(PathUtils.normalize(target));
			location.setUpdatedAt(LocalDateTime.now(clock));

			catalogFileLocationRepository.save(location);
		}

		return true;
	}
}