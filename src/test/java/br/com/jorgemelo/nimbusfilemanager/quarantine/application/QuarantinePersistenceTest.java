package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.ContentReconciliation;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;
import br.com.jorgemelo.nimbusfilemanager.shared.AppliedLocationChanges;
import br.com.jorgemelo.nimbusfilemanager.shared.PreparedMovements;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.LocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLocationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.MovementWriter;

/**
 * Four statements about one quarantined file, and none of them may stand in for
 * another.
 *
 * <p>
 * The one this guards hardest is the fact: the bytes were <em>moved</em>, and
 * recording that as a deletion would say they are gone when they are one restore
 * away. That the catalog stops counting the file is a different statement, on a
 * different column.
 */
class QuarantinePersistenceTest {

	private static final Instant NOW = Instant.parse("2026-08-12T10:00:00Z");
	private static final long EXECUTION_ID = 42L;

	private final CatalogLocationWriter catalogLocationWriter = mock(CatalogLocationWriter.class);
	private final ContentReconciliation contentReconciliation = mock(ContentReconciliation.class);
	private final MovementWriter movementWriter = mock(MovementWriter.class);
	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);

	private final QuarantinePersistence persistence = new QuarantinePersistence(catalogLocationWriter,
			contentReconciliation, movementWriter, catalogFileRepository, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void recordsTheMoveUnderTheIdentityTheOperationReserved(@TempDir Path folder) {
		Path original = folder.resolve("a.jpg");
		Path quarantine = folder.resolve("q").resolve("7__a.jpg");

		PreparedMovement operation = operation(original, quarantine);

		persistence.persistQuarantine(EXECUTION_ID, operation, active(), original, quarantine, moved());

		LocationChange change = relocation();

		assertThat(change.eventId()).isEqualTo(operation.catalogFileEventPublicId());
		assertThat(change.expectedCurrentPath()).isEqualTo(original);
		assertThat(change.newPath()).isEqualTo(quarantine);
		assertThat(change.provenance().source()).isEqualTo(CatalogEventSources.QUARANTINE);
		assertThat(change.provenance().evidence()).isEqualTo(CatalogEventEvidence.NIMBUS_OPERATION);
		assertThat(change.provenance().occurredAt()).isEqualTo(NOW);
	}

	/** Where the file is and whether the catalog counts it are two questions. */
	@Test
	void marksTheEntryRemovedWithoutSayingTheBytesWere(@TempDir Path folder) {
		CatalogFile catalogFile = active();

		Path original = folder.resolve("a.jpg");
		Path quarantine = folder.resolve("q").resolve("7__a.jpg");

		persistence.persistQuarantine(EXECUTION_ID, operation(original, quarantine), catalogFile, original,
				quarantine, moved());

		assertThat(catalogFile.getLifecycleStatus()).isEqualTo(LifecycleStatus.DELETED);

		verify(catalogFileRepository).save(catalogFile);

		// The one fact is the move. A second one saying the file was deleted would
		// claim the bytes are gone when they are one restore away.
		assertThat(relocation().newPath()).isEqualTo(quarantine);
	}

	@Test
	void settlesTheOperationThatWasWaitingForThisMove(@TempDir Path folder) {
		Path original = folder.resolve("a.jpg");
		Path quarantine = folder.resolve("q").resolve("7__a.jpg");

		PreparedMovement operation = operation(original, quarantine);

		persistence.persistQuarantine(EXECUTION_ID, operation, active(), original, quarantine, moved());

		verify(movementWriter).markMoved(EXECUTION_ID, List.of(operation.movementPublicId()));
	}

	/**
	 * Restoring is an operation of its own, not the quarantine run backwards: the
	 * file may not even go back where it came from. The one that put it there moved
	 * a file, which remains true, and is left alone.
	 */
	@Test
	void restoringIsItsOwnOperationAndLeavesTheQuarantineOneAlone(@TempDir Path folder) {
		CatalogFile catalogFile = active();

		catalogFile.markDeleted();

		Path quarantine = folder.resolve("q").resolve("7__a.jpg");
		Path destination = folder.resolve("library").resolve("a.jpg");

		PreparedMovement quarantined = operation(folder.resolve("a.jpg"), quarantine);
		PreparedMovement restore = operation(quarantine, destination);

		persistence.persistRestore(EXECUTION_ID, restore, catalogFile, quarantine, destination, moved());

		LocationChange change = relocation();

		assertThat(change.eventId()).isEqualTo(restore.catalogFileEventPublicId());
		assertThat(change.expectedCurrentPath()).isEqualTo(quarantine);
		assertThat(change.newPath()).isEqualTo(destination);

		// The catalog counts the entry again, and what explains that in the history is
		// this operation's own move - not a lifecycle written on its own.
		assertThat(catalogFile.getLifecycleStatus()).isEqualTo(LifecycleStatus.ACTIVE);

		verify(movementWriter).markMoved(EXECUTION_ID, List.of(restore.movementPublicId()));
		verify(movementWriter, never()).markUndone(List.of(quarantined.movementPublicId()));
	}

	/**
	 * The digest the secure move already proved, handed to the one place that
	 * decides what it means. No file is read again: the move read it twice to
	 * verify itself, and this is that answer rather than a new one.
	 */
	@Test
	void handsTheDigestTheMoveProvedToTheReconciliation(@TempDir Path folder) {
		CatalogFile catalogFile = active();

		Path original = folder.resolve("a.jpg");
		Path quarantine = folder.resolve("q").resolve("7__a.jpg");

		persistence.persistQuarantine(EXECUTION_ID, operation(original, quarantine), catalogFile, original,
				quarantine, moved());

		verify(contentReconciliation).reconcileFromDigest(catalogFile, "digest-proved-by-the-move", 1024L,
				CatalogEventSources.QUARANTINE, NOW);
	}

	private LocationChange relocation() {
		ArgumentCaptor<LocationChange> change = ArgumentCaptor.forClass(LocationChange.class);

		verify(catalogLocationWriter).relocate(change.capture());

		return change.getValue();
	}

	private CatalogFile active() {
		CatalogFile catalogFile = CatalogFile.builder().id(7L).lifecycleStatus(LifecycleStatus.ACTIVE).build();

		// A file has a place, and quarantining it is moving it from there.
		catalogFile.setLocation(CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath("D:\\library\\a.jpg").pathFlavor(PathFlavor.WINDOWS).build());

		return catalogFile;
	}

	/** The placement as the row now holds it, which the entry has to agree with. */
	@BeforeEach
	void thePlacementDoorAnswers() {
		when(catalogLocationWriter.relocate(any()))
				.thenAnswer(invocation -> AppliedLocationChanges.applying(invocation.getArgument(0)));
	}

	private PreparedMovement operation(Path source, Path target) {
		return PreparedMovements.pending(1L, 7L, source, target);
	}

	/** What the verified move proved about the file it placed. */
	private MoveBaseline moved() {
		return new MoveBaseline(1024L, "digest-proved-by-the-move");
	}
}