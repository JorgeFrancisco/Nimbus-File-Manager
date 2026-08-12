package br.com.jorgemelo.nimbusfilemanager.shared.application.catalog;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityPurgeWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.FolderRelocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.FolderPlacementRow;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLocationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

/**
 * The one implementation behind both catalog ports.
 *
 * <p>
 * Two interfaces and one class, because the split is about authority rather
 * than about code: converging what the file system already did and destroying
 * catalogued history are different powers to grant, and the same statements
 * serve both. Splitting the implementation as well would be two paths to the
 * same table.
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
public class CollectionCatalogMutations implements CatalogConvergenceMutations, CatalogCollectionMutations {

	private final CatalogFileRepository catalogFileRepository;
	private final CatalogFileLocationRepository catalogFileLocationRepository;
	private final CatalogLocationWriter catalogLocationWriter;
	private final SimilarityPurgeWriter similarityPurgeWriter;
	private final EligibilityAnnouncer eligibilityAnnouncer;

	public CollectionCatalogMutations(CatalogFileRepository catalogFileRepository,
			CatalogFileLocationRepository catalogFileLocationRepository,
			CatalogLocationWriter catalogLocationWriter, SimilarityPurgeWriter similarityPurgeWriter,
			EligibilityAnnouncer eligibilityAnnouncer) {
		this.catalogFileRepository = catalogFileRepository;
		this.catalogFileLocationRepository = catalogFileLocationRepository;
		this.catalogLocationWriter = catalogLocationWriter;
		this.similarityPurgeWriter = similarityPurgeWriter;
		this.eligibilityAnnouncer = eligibilityAnnouncer;
	}

	@Override
	@Transactional
	public int purgeMissingBefore(Instant cutoff) {
		return forgotten(catalogFileRepository.deleteMissingBefore(cutoff));
	}

	/**
	 * What a hard purge owes the rest of the catalog. The files are gone for good,
	 * so a published analysis that still names them is describing a library that
	 * does not exist - and the set the next analysis runs over has changed, which
	 * is announced the way every other mutation of it announces: after the commit,
	 * through the event, never by calling similarity from here.
	 */
	private int forgotten(int purged) {
		if (purged > 0) {
			similarityPurgeWriter.forgetPurgedFiles();

			eligibilityAnnouncer.announce("hard purge");
		}

		return purged;
	}

	/**
	 * Forgetting a library forgets what is <em>in</em> it.
	 *
	 * <p>
	 * A file catalogued here and since moved somewhere else is no longer part of
	 * this library, and switching away from it does not remove that file - which is
	 * a change: the previous query also caught files by the root they were first
	 * discovered under, and so deleted a file the user had moved into the library
	 * being adopted. Where a file belongs is where it is.
	 *
	 * <p>
	 * The boundary is the root itself or anything under it, and "under" means
	 * followed by a separator: a sibling folder whose name merely begins the same -
	 * {@code Fotos} beside {@code FotosAntigas} - is a different library.
	 */
	@Override
	@Transactional
	public int forgetLibrary(String libraryRoot) {
		var root = PathUtils.normalizePath(libraryRoot);

		return forgotten(catalogFileRepository.deleteWithinLibrary(PathUtils.normalize(root),
				PathFlavor.of(root).name()));
	}

	/**
	 * Two statements, and only two, whatever the size of the folder: one asks which
	 * files are under it, one moves them and records a fact for each.
	 *
	 * <p>
	 * The list travels from the first to the second rather than the second finding
	 * the files itself, and that is what makes the operation checkable: the write
	 * refuses if the folder no longer holds exactly those files, instead of quietly
	 * moving a set nobody decided on.
	 */
	@Override
	@Transactional
	public int repointFolder(String oldFolder, String newFolder, Instant occurredAt, String source,
			String evidence) {
		Path from = PathUtils.normalizePath(oldFolder);

		List<Long> under = catalogFileLocationRepository
				.findPlacementsUnderFolder(PathUtils.normalize(from), PathFlavor.of(from).name()).stream()
				.map(FolderPlacementRow::getCatalogFileId).toList();

		// Minted here because this caller observed a folder move rather than making
		// one: there is no operation of ours whose identities could have been reserved
		// before it happened.
		List<UUID> eventIds = Stream.generate(UuidV7::generate).limit(under.size()).toList();

		return repointFolder(oldFolder, newFolder, under, eventIds,
				new CatalogFactProvenance(occurredAt, source, evidence, null));
	}

	@Override
	@Transactional
	public int repointFolder(String oldFolder, String newFolder, List<Long> catalogFileIds,
			List<UUID> catalogFileEventPublicIds, CatalogFactProvenance provenance) {
		return catalogLocationWriter.relocateFolderContents(catalogFileIds, catalogFileEventPublicIds,
				new FolderRelocation(PathUtils.normalizePath(oldFolder), PathUtils.normalizePath(newFolder),
						provenance));
	}
}