package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.CatalogTimestamp;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.ContentReconciliation;
import br.com.jorgemelo.nimbusfilemanager.shared.AppliedLocationChanges;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.LocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLocationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.MovementWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Closing a move means three things committing together, and this is where the
 * three are named.
 *
 * <p>
 * What it guards above all is that the fact is recorded under the identity the
 * operation reserved before the file was touched. Minting one here instead would
 * compile, pass a careless test, and leave every retry writing a second fact for
 * one move.
 */
class OrganizationMovePersistenceTest {

	private static final Instant NOW = Instant.parse("2026-08-12T10:00:00Z");
	private static final long EXECUTION_ID = 42L;

	private final CatalogLocationWriter catalogLocationWriter = mock(CatalogLocationWriter.class);
	private final ContentReconciliation contentReconciliation = mock(ContentReconciliation.class);
	private final MovementWriter movementWriter = mock(MovementWriter.class);
	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);

	private final OrganizationMovePersistence persistence = new OrganizationMovePersistence(catalogLocationWriter,
			contentReconciliation, movementWriter, catalogFileRepository, Clock.fixed(NOW, ZoneOffset.UTC));

	/**
	 * An entry as one arrives here: catalogued, and at the place it is leaving.
	 */
	private CatalogFile catalogued(long id, Long sizeBytes) {
		CatalogFile catalogFile = CatalogFile.builder().id(id).sizeBytes(sizeBytes).modifiedAt(NOW).build();

		catalogFile.setLocation(CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath("D:\\" + id + "\\before.jpg").pathFlavor(PathFlavor.WINDOWS).build());

		return catalogFile;
	}

	@BeforeEach
	void thePlacementDoorAnswers() {
		when(catalogLocationWriter.relocate(Mockito.any()))
				.thenAnswer(invocation -> AppliedLocationChanges.applying(invocation.getArgument(0)));
	}

	@Test
	void recordsTheFactUnderTheIdentityTheOperationReserved(@TempDir Path folder) throws IOException {
		PreparedMovement operation = operation();
		CatalogFile catalogFile = catalogued(7L, 10L);

		Path source = Files.createFile(folder.resolve("origem.jpg"));
		Path target = folder.resolve("2026").resolve("origem.jpg");

		persistence.persistSuccessfulMove(EXECUTION_ID, operation, catalogFile, source, target, null);

		ArgumentCaptor<LocationChange> change = ArgumentCaptor.forClass(LocationChange.class);

		verify(catalogLocationWriter).relocate(change.capture());

		assertThat(change.getValue().eventId()).isEqualTo(operation.catalogFileEventPublicId());
		assertThat(change.getValue().catalogFileId()).isEqualTo(7L);
		assertThat(change.getValue().expectedCurrentPath()).isEqualTo(source);
		assertThat(change.getValue().newPath()).isEqualTo(target);
		assertThat(change.getValue().provenance().source()).isEqualTo(CatalogEventSources.ORGANIZATION);
	}

	@Test
	void settlesTheOperationThatWasWaitingForThisMove(@TempDir Path folder) throws IOException {
		PreparedMovement operation = operation();

		persistence.persistSuccessfulMove(EXECUTION_ID, operation,
				catalogued(7L, null), Files.createFile(folder.resolve("a.jpg")),
				folder.resolve("2026").resolve("a.jpg"), null);

		verify(movementWriter).markMoved(EXECUTION_ID, List.of(operation.movementPublicId()));
	}

	/**
	 * The order is not decoration. Settling first would leave a window in which the
	 * operation says done and no fact exists - and a crash inside it is exactly the
	 * state this whole design exists to make unreachable.
	 */
	@Test
	void recordsTheFactBeforeSettlingTheOperation(@TempDir Path folder) throws IOException {
		PreparedMovement operation = operation();

		persistence.persistSuccessfulMove(EXECUTION_ID, operation,
				catalogued(7L, null), Files.createFile(folder.resolve("a.jpg")),
				folder.resolve("2026").resolve("a.jpg"), null);

		InOrder order = Mockito.inOrder(catalogLocationWriter, movementWriter);

		order.verify(catalogLocationWriter).relocate(Mockito.any());
		order.verify(movementWriter).markMoved(Mockito.anyLong(), Mockito.any());
	}

	/**
	 * The entry that gets saved says the file is at its destination.
	 *
	 * <p>
	 * This object was read before the move and still names the place the file
	 * left, while the row already names where it went - and saving it is a merge
	 * that cascades to the placement. Left as read, the move was written to disk,
	 * recorded as a fact, and then undone in the catalog: every later pass found
	 * the file listed at a source it had already left, planned the same move
	 * again, and performed it again.
	 */
	@Test
	void theEntryItSavesNamesWhereTheFileWentNotWhereItLeft(@TempDir Path folder) throws IOException {
		CatalogFile catalogFile = catalogued(7L, null);

		Path target = folder.resolve("2026").resolve("a.jpg");

		persistence.persistSuccessfulMove(EXECUTION_ID, operation(), catalogFile,
				Files.createFile(folder.resolve("a.jpg")), target, null);

		ArgumentCaptor<CatalogFile> saved = ArgumentCaptor.forClass(CatalogFile.class);

		verify(catalogFileRepository).save(saved.capture());

		assertThat(saved.getValue().getLocation().getCurrentPath()).isEqualTo(PathUtils.normalize(target));
	}

	/**
	 * A move is when the file was last written as far as anyone here can tell - at
	 * the precision the catalog keeps, because this is the value that becomes
	 * {@code CatalogFile.modifiedAt}. Storing the nanoseconds the filesystem
	 * reports would write one number and read back another, and the next pass to
	 * compare the two would call it a change.
	 */
	@Test
	void readsBackWhenTheFileWasLastWrittenAtThePrecisionTheCatalogKeeps(@TempDir Path folder) throws IOException {
		CatalogFile catalogFile = catalogued(7L, null);

		Path target = Files.createFile(folder.resolve("chegou.jpg"));

		persistence.persistSuccessfulMove(EXECUTION_ID, operation(), catalogFile, folder.resolve("saiu.jpg"), target,
				null);

		assertThat(catalogFile.getModifiedAt())
				.isEqualTo(CatalogTimestamp.observed(Files.getLastModifiedTime(target)));

		verify(catalogFileRepository).save(catalogFile);
	}

	private PreparedMovement operation() {
		return new PreparedMovement(1L, UUID.randomUUID(), UUID.randomUUID(), 7L, "origem", "destino",
				MovementStatus.PENDING);
	}
}