package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.CatalogPathMatch;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.ResolvedCatalogFile;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.mapper.CatalogFileMapper;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * Turns a file found on disk into the catalog entry that should be written for
 * it - which one it is, and what it should say.
 *
 * <p>
 * Both halves of that used to sit inside the persistence service, next to
 * transactions, geo resolution and metrics, and the class had grown to hold all
 * of them at once. Deciding <em>which file this is</em> is a question about
 * identity; writing it is a question about storage; they change for different
 * reasons and are now answered in different places.
 *
 * <p>
 * Nothing here persists anything. It reads what the catalog already knows and
 * hands back an entity for the caller to write - which keeps the transaction
 * boundary where the writing happens rather than where the deciding does.
 */
@Component
public class InventoryCatalogResolver {

	private final CatalogPathMatcher catalogPathMatcher;
	private final CatalogFileRepository catalogFileRepository;
	private final CatalogFileMapper catalogFileMapper;

	public InventoryCatalogResolver(CatalogPathMatcher catalogPathMatcher,
			CatalogFileRepository catalogFileRepository, CatalogFileMapper catalogFileMapper) {
		this.catalogPathMatcher = catalogPathMatcher;
		this.catalogFileRepository = catalogFileRepository;
		this.catalogFileMapper = catalogFileMapper;
	}

	/**
	 * Whether the catalog has anything on record for this path - present or
	 * missing. It is what a scan asks before deciding to open the file at all.
	 */
	public boolean knows(Path file) {
		return !catalogPathMatcher.match(List.of(file)).isEmpty();
	}

	/**
	 * Which of these paths already hold a file that is present - the cache hits a
	 * scan may skip without opening.
	 *
	 * <p>
	 * Present rather than merely known, and that distinction is worth the words: a
	 * file the catalog remembers at a path it has since left is not a cache hit,
	 * and counting it as one is what made an inventory load every entity of every
	 * batch to find the few that needed reviving.
	 */
	public Set<String> present(List<Path> files) {
		return catalogPathMatcher.match(files).values().stream().filter(match -> match.presentFileId() != null)
				.map(CatalogPathMatch::inputPath).collect(Collectors.toCollection(LinkedHashSet::new));
	}

	/** The entry this path belongs to, when the path is enough to tell. */
	public Optional<CatalogFile> existing(Path file, boolean withDetails) {
		return catalogPathMatcher.match(List.of(file)).values().stream().findFirst()
				.flatMap(CatalogPathMatch::resolvedFileId).flatMap(id -> load(id, withDetails));
	}

	/**
	 * The entries a whole batch belongs to, keyed by the caller's own path.
	 *
	 * <p>
	 * Two queries for any batch size: one matches paths to identities, one loads
	 * those identities. A path absent from the result is a file the catalog has
	 * never known - or one it cannot tell apart from several it lost track of, in
	 * which case claiming any of them would be worse than treating it as new.
	 */
	public Map<String, CatalogFile> existing(List<Path> files, boolean withDetails) {
		Map<String, CatalogPathMatch> matches = catalogPathMatcher.match(files);

		Map<Long, String> pathById = new LinkedHashMap<>();

		matches.forEach((path, match) -> match.resolvedFileId().ifPresent(id -> pathById.put(id, path)));

		if (pathById.isEmpty()) {
			return Map.of();
		}

		List<CatalogFile> loaded = withDetails ? catalogFileRepository.findWithDetailsByIdIn(pathById.keySet())
				: catalogFileRepository.findAllById(pathById.keySet());

		Map<String, CatalogFile> byPath = new LinkedHashMap<>();

		loaded.forEach(file -> byPath.put(pathById.get(file.getId()), file));

		return byPath;
	}

	/**
	 * What the catalog should say about this file now, whether it is meeting it for
	 * the first time or seeing it again.
	 */
	public ResolvedCatalogFile catalogue(CatalogFile existing, Path file, MetadataResult metadata) {
		if (existing == null) {
			return new ResolvedCatalogFile(catalogFileMapper.toEntity(file, metadata), false, true);
		}

		return new ResolvedCatalogFile(existing, catalogFileMapper.updateEntity(existing, file, metadata), false);
	}

	private Optional<CatalogFile> load(Long id, boolean withDetails) {
		return withDetails ? catalogFileRepository.findWithDetailsByIdIn(List.of(id)).stream().findFirst()
				: catalogFileRepository.findById(id);
	}
}