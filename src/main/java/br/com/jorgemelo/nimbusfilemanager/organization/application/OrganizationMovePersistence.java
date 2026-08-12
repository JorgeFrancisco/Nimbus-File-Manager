package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.CatalogTimestamp;
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
import lombok.extern.slf4j.Slf4j;

/**
 * Closing one organization move: the fact, the placement and the operation, in
 * one transaction.
 *
 * <p>
 * The operation was written before the file was touched and already carries the
 * identity this fact will have, so what happens here is not a decision - it is
 * the commit that makes a move real. A retry arriving with the same identity
 * gets the same fact recorded once, which is the whole reason the identity was
 * reserved rather than minted now.
 *
 * <p>
 * All three have to close together. Committing the fact and leaving the
 * operation pending would tell a later attempt to do the work again, and the
 * catalog would then hold two facts for one move - which is the state this
 * front exists to make impossible.
 *
 * <p>
 * Its own bean, and not a method inside {@link OrganizationExecutor}, because
 * Spring's {@code @Transactional} does not apply to self-invocation: the
 * annotation on a method the executor called itself would open no transaction
 * at all.
 */
@Slf4j
@Service
public class OrganizationMovePersistence {

	private final CatalogLocationWriter catalogLocationWriter;
	private final ContentReconciliation contentReconciliation;
	private final MovementWriter movementWriter;
	private final CatalogFileRepository catalogFileRepository;
	private final Clock clock;

	public OrganizationMovePersistence(CatalogLocationWriter catalogLocationWriter,
			ContentReconciliation contentReconciliation, MovementWriter movementWriter,
			CatalogFileRepository catalogFileRepository, Clock clock) {
		this.catalogLocationWriter = catalogLocationWriter;
		this.contentReconciliation = contentReconciliation;
		this.movementWriter = movementWriter;
		this.catalogFileRepository = catalogFileRepository;
		this.clock = clock;
	}

	/**
	 * Records where the file went and marks the operation done.
	 *
	 * <p>
	 * Whether this counts as a move or a rename is worked out from the two paths
	 * rather than asserted: organizing computes a destination folder and keeps the
	 * file's own name, so which of the two it turns out to be is an outcome.
	 */
	@Transactional
	public void persistSuccessfulMove(long executionId, PreparedMovement operation, CatalogFile catalogFile,
			Path source, Path target, MoveBaseline moved) {
		AppliedLocationChange applied = catalogLocationWriter.relocate(new LocationChange(catalogFile.getId(),
				operation.catalogFileEventPublicId(), source, target,
				new CatalogFactProvenance(Instant.now(clock), CatalogEventSources.ORGANIZATION,
						CatalogEventEvidence.NIMBUS_OPERATION, null)));

		// The entry was read before the move and still names the place the file left,
		// while the row already names where it went - and the save below is a merge.
		catalogFile.getLocation().placedAt(applied.currentPath(), applied.pathKey(), applied.currentFolder());

		// The file itself is not where it was: what it is has not changed, but when it
		// was last written may have, and the move is the moment to read it.
		catalogFile.setModifiedAt(lastModified(target, catalogFile.getModifiedAt()));

		// The digest the move already proved, handed to the one place that decides what
		// it means: nothing new when it agrees, a first digest when the catalog had
		// none, and a content change when it does not - which the move did not cause.
		contentReconciliation.reconcileFromDigest(catalogFile, moved == null ? null : moved.sha256(),
				moved == null ? null : moved.sizeBytes(), CatalogEventSources.ORGANIZATION, Instant.now(clock));

		catalogFileRepository.save(catalogFile);

		movementWriter.markMoved(executionId, List.of(operation.movementPublicId()));
	}

	private Instant lastModified(Path file, Instant fallback) {
		try {
			return CatalogTimestamp.observed(Files.getLastModifiedTime(file));
		} catch (IOException _) {
			return fallback;
		}
	}
}