package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.ContentReconciliation;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.AppliedLocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.LocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLocationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.MovementWriter;

/**
 * Closing a quarantine: four things that have to be true together.
 *
 * <p>
 * A quarantined file is four statements at once, and they are about different
 * dimensions. The <em>operation</em> is the movement. The <em>fact</em> is that
 * the bytes went from the library to the quarantine folder - a move, not a
 * deletion. The <em>projection</em> is the file's last known place. The
 * <em>lifecycle</em> is that the catalog now treats it as removed. Collapsing
 * any two of them loses something: recording the fact as a deletion would say
 * the bytes are gone when they are one restore away.
 *
 * <p>
 * Composed here rather than in a database function on purpose. What the
 * structural door knows is where a file is; teaching it about DELETED, about
 * quarantine reasons and about movements would put this feature's vocabulary
 * inside the one thing every feature shares. Composition costs one transaction
 * boundary and buys that separation - the same trade organization made, with one
 * more write inside the same transaction.
 *
 * <p>
 * Both directions live here because they are the same aggregate seen twice and
 * compose the same three collaborators - splitting them would leave two classes
 * with identical dependencies differing only in which way the lifecycle moves.
 * What they are <em>not</em> is inverses: a restore is its own operation with
 * its own identity, and the quarantine it follows keeps saying, truthfully,
 * that the file was once moved out of the library.
 *
 * <p>
 * Not to be confused with {@code QuarantinePurgePersistence}: this puts files in
 * and takes them out, that one erases them for good.
 */
@Service
public class QuarantinePersistence {

	private final CatalogLocationWriter catalogLocationWriter;
	private final ContentReconciliation contentReconciliation;
	private final MovementWriter movementWriter;
	private final CatalogFileRepository catalogFileRepository;
	private final Clock clock;

	public QuarantinePersistence(CatalogLocationWriter catalogLocationWriter,
			ContentReconciliation contentReconciliation, MovementWriter movementWriter,
			CatalogFileRepository catalogFileRepository, Clock clock) {
		this.catalogLocationWriter = catalogLocationWriter;
		this.contentReconciliation = contentReconciliation;
		this.movementWriter = movementWriter;
		this.catalogFileRepository = catalogFileRepository;
		this.clock = clock;
	}

	/**
	 * The file is in quarantine: record the move, follow it with the placement, mark
	 * the catalog entry removed and settle the operation - all or none.
	 *
	 * <p>
	 * The fact is recorded under the identity the operation reserved before the
	 * file was touched, so an attempt that has already done this finds the fact
	 * already there rather than writing a second one.
	 *
	 * @param operation the movement prepared before the file moved, still pending
	 */
	@Transactional
	public void persistQuarantine(long executionId, PreparedMovement operation, CatalogFile catalogFile, Path original,
			Path quarantine, MoveBaseline moved) {
		AppliedLocationChange applied = catalogLocationWriter.relocate(new LocationChange(catalogFile.getId(),
				operation.catalogFileEventPublicId(), original, quarantine,
				new CatalogFactProvenance(Instant.now(clock), CatalogEventSources.QUARANTINE,
						CatalogEventEvidence.NIMBUS_OPERATION, null)));

		// Where the file is now, so the save below does not merge the old place back
		// over it - a restore reads this to know where the file is coming from, and
		// would find it still listed in the library it was taken out of.
		catalogFile.getLocation().placedAt(applied.currentPath(), applied.pathKey(), applied.currentFolder());

		// A separate dimension from where the file is: the bytes moved, and the catalog
		// stops counting the entry among the files it has. Saying either one of those
		// twice - a DELETED event, or a lifecycle inferred from the path - would make
		// two answers out of one truth.
		catalogFile.markDeleted();

		catalogFileRepository.save(catalogFile);

		movementWriter.markMoved(executionId, List.of(operation.movementPublicId()));

		learn(catalogFile, moved);
	}

	/**
	 * The file is back in the library: record the move out of quarantine, follow it
	 * with the placement, let the catalog count the entry again and settle the
	 * operation - all or none.
	 *
	 * <p>
	 * The quarantine operation that put the file there is deliberately untouched.
	 * It moved the file, which remains true, and a restore is not its reversal -
	 * the destination may not even be where the file came from. Marking it undone
	 * would rewrite one operation's record with another operation's outcome.
	 *
	 * @param operation the restore movement, prepared before the file moved
	 */
	@Transactional
	public void persistRestore(long executionId, PreparedMovement operation, CatalogFile catalogFile, Path quarantine,
			Path destination, MoveBaseline moved) {
		AppliedLocationChange applied = catalogLocationWriter.relocate(new LocationChange(catalogFile.getId(),
				operation.catalogFileEventPublicId(), quarantine, destination,
				new CatalogFactProvenance(Instant.now(clock), CatalogEventSources.QUARANTINE,
						CatalogEventEvidence.NIMBUS_OPERATION, null)));

		catalogFile.getLocation().placedAt(applied.currentPath(), applied.pathKey(), applied.currentFolder());

		catalogFile.markActive();

		catalogFileRepository.save(catalogFile);

		movementWriter.markMoved(executionId, List.of(operation.movementPublicId()));

		learn(catalogFile, moved);
	}

	/**
	 * The digest the secure move already proved, handed to the one place that
	 * decides what it means. No file is read again: the move read it twice to
	 * verify itself, and this is that answer rather than a new one.
	 */
	private void learn(CatalogFile catalogFile, MoveBaseline moved) {
		if (moved == null) {
			return;
		}

		contentReconciliation.reconcileFromDigest(catalogFile, moved.sha256(), moved.sizeBytes(),
				CatalogEventSources.QUARANTINE, Instant.now(clock));
	}
}