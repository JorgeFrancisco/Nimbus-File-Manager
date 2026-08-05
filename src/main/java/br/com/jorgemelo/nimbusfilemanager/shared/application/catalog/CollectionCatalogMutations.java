package br.com.jorgemelo.nimbusfilemanager.shared.application.catalog;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * The one implementation of {@link CatalogMutations}.
 *
 * <p>
 * Thin on purpose. The set-based statements themselves belong to the repository,
 * where the SQL and the cascade rules are written and tested; what this adds is
 * the boundary - a named, injectable capability that architecture rules can see,
 * where a repository method is indistinguishable from the dozens of others any
 * class may legitimately call. The value is in who is allowed to hold it, not in
 * what it computes.
 *
 * <p>
 * The library root is normalised here rather than by each caller, because the
 * two forms the query needs - the root itself and the root plus a separator -
 * have to agree, and a caller that built only one of them would match either too
 * much or too little.
 */
@Component
public class CollectionCatalogMutations implements CatalogMutations {

	private final CatalogFileRepository catalogFileRepository;
	private final CatalogFileLocationRepository catalogFileLocationRepository;

	public CollectionCatalogMutations(CatalogFileRepository catalogFileRepository,
			CatalogFileLocationRepository catalogFileLocationRepository) {
		this.catalogFileRepository = catalogFileRepository;
		this.catalogFileLocationRepository = catalogFileLocationRepository;
	}

	@Override
	@Transactional
	public int markMissing(List<Long> catalogFileIds, LocalDateTime changedAt) {
		return catalogFileRepository.markMissingByIds(catalogFileIds, changedAt);
	}

	@Override
	@Transactional
	public int purgeMissingBefore(LocalDateTime cutoff) {
		return catalogFileRepository.deleteMissingBefore(cutoff);
	}

	@Override
	@Transactional
	public int forgetLibrary(String libraryRoot) {
		var root = PathUtils.normalizePath(libraryRoot);

		String rootText = PathUtils.normalize(root);

		return catalogFileRepository.deleteWithinLibrary(rootText,
				rootText + root.getFileSystem().getSeparator());
	}

	/**
	 * Both tables in one transaction, because a catalog whose {@code file_key}
	 * moved while its placement did not is the exact state reconciliation calls a
	 * stale path and spends a pass repairing.
	 */
	@Override
	@Transactional
	public int repointFolder(String oldFolder, String newFolder, LocalDateTime changedAt) {
		var from = PathUtils.normalizePath(oldFolder);
		var to = PathUtils.normalizePath(newFolder);

		String fromText = PathUtils.normalize(from);
		String toText = PathUtils.normalize(to);

		String separator = from.getFileSystem().getSeparator();

		int repointed = catalogFileRepository.repointFileKeys(fromText + separator, toText + separator);

		catalogFileLocationRepository.repointLocations(fromText, toText, fromText + separator, toText + separator,
				changedAt);

		return repointed;
	}
}