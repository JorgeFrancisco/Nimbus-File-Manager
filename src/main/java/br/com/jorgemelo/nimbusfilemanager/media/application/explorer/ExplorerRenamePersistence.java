package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.ContentReconciliation;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.AppliedLocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.LocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLocationWriter;
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
 * The placement is not written here. It goes through {@link
 * CatalogLocationWriter}, which is the only thing in the product allowed to
 * change where a file is, and which records that the rename happened as well as
 * applying it. What is left here is the part that is about this feature: which
 * file the path belongs to, and the extension, which a rename can change and
 * which is not part of where the file is.
 */
@Component
public class ExplorerRenamePersistence {

	private final CatalogFileRepository catalogFileRepository;
	private final CatalogFileLocationRepository catalogFileLocationRepository;
	private final CatalogLocationWriter catalogLocationWriter;
	private final ContentReconciliation contentReconciliation;
	private final Clock clock;

	public ExplorerRenamePersistence(CatalogFileRepository catalogFileRepository,
			CatalogFileLocationRepository catalogFileLocationRepository, CatalogLocationWriter catalogLocationWriter,
			ContentReconciliation contentReconciliation,
			Clock clock) {
		this.catalogFileRepository = catalogFileRepository;
		this.catalogFileLocationRepository = catalogFileLocationRepository;
		this.catalogLocationWriter = catalogLocationWriter;
		this.contentReconciliation = contentReconciliation;
		this.clock = clock;
	}

	/**
	 * Points the catalogued file at its new name. A file the catalog never knew
	 * about is renamed on disk and nothing is written here - there is no row to
	 * correct, and inventing one would be inventing history.
	 *
	 * <p>
	 * The identity comes from the movement this rename prepared before it touched
	 * the disk, not from the execution that ordered it. One run may order many
	 * operations - a folder rename is one execution and a fact per file - so a fact
	 * named after the run would be one identity for many facts, and the second
	 * would be refused as an idempotency conflict. Coming from the movement also
	 * means a retry brings back the identity the first attempt was going to use,
	 * without reading the catalog it is about to write.
	 *
	 * @return whether a catalogued file was repointed
	 */
	@Transactional
	public boolean rename(Path source, Path target, UUID catalogFileEventPublicId, MoveBaseline moved) {
		Optional<CatalogFile> stored = catalogFileLocationRepository.findPresentByPath(PathUtils.normalize(source),
				PathFlavor.of(source).name());

		if (stored.isEmpty()) {
			return false;
		}

		CatalogFile file = stored.get();

		AppliedLocationChange applied = catalogLocationWriter
				.rename(new LocationChange(file.getId(), catalogFileEventPublicId, source, target,
						new CatalogFactProvenance(Instant.now(clock), CatalogEventSources.EXPLORER,
								CatalogEventEvidence.NIMBUS_OPERATION, null)));

		// The new name, so the save below does not merge the old one back over it.
		file.getLocation().placedAt(applied.currentPath(), applied.pathKey(), applied.currentFolder());

		// After the placement and not before it: the writer reads these tables
		// directly, so anything left pending in the persistence context would not be
		// there for it to see.
		file.setExtension(ExtensionUtils.fromPath(target));

		catalogFileRepository.save(file);

		// The digest the rename's own secure move proved, in the same transaction as
		// the repoint. A rename does not change bytes, so agreeing is the ordinary
		// outcome - and disagreeing means the catalog was already stale, which this
		// is the first thing in a position to notice.
		if (moved != null) {
			contentReconciliation.reconcileFromDigest(file, moved.sha256(), moved.sizeBytes(),
					CatalogEventSources.EXPLORER, Instant.now(clock));
		}

		return true;
	}
}