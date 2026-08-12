package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.ContentReconciliation;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.GrantingOperationLocks;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLock;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OwnershipLostException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.constants.OrganizationMessages;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.shared.AppliedLocationChanges;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.PreparedMovements;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWriteOff;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.LocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.MovementRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLocationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.MovementWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

@ExtendWith(MockitoExtension.class)
class OrganizationUndoServiceTest {

	private final SelfWrittenPathRegistry pathRegistry = new SelfWriteOff();
	/** What each file's reversal turned out to be, keyed the way the run keys it. */
	private final Map<Long, PreparedMovement> reserved = new LinkedHashMap<>();

	@TempDir
	Path tempDir;

	@Mock
	private EligibilityAnnouncer eligibilityAnnouncer;

	@Mock
	private ExecutionRepository executionRepository;

	@Mock
	private CatalogFileRepository catalogFileRepository;

	@Mock
	private CatalogLocationWriter catalogLocationWriter;

	@Mock
	private ContentReconciliation contentReconciliation;

	@Mock
	private MovementWriter movementWriter;

	@Mock
	private MovementRepository movementRepository;

	@Mock
	private ExecutionProgressService executionProgressService;

	@Mock
	private ExecutionCancellationService executionCancellationService;

	private final OperationLockService operationLockService = GrantingOperationLocks.granting();

	@Test
	void undoShouldMoveFileBackAndUpdateDatabase() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path source = sourceFolder.resolve("photo.jpg");
		Path target = Files.writeString(targetFolder.resolve("photo.jpg"), "content");

		CatalogFile catalogFile = catalogFile(10L, target);

		Movement movement = movement(100L, catalogFile, source, target, MovementStatus.MOVED);

		reversing();

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED)))
						.thenReturn(List.of(movement));

		ExecutionCounts undo = undo(service(), 1L);

		Assertions.assertThat(finishedStatus()).isEqualTo(ExecutionStatus.FINISHED);
		Assertions.assertThat(undo.moved()).isEqualTo(1);
		Assertions.assertThat(undo.failed()).isZero();
		Assertions.assertThat(Files.exists(source)).isTrue();
		Assertions.assertThat(Files.exists(target)).isFalse();

		// Where the file is now is stated to the write door, which is the only thing
		// allowed to move a placement - and stated the way a reversal is: from where
		// the forward move put it, back to where it came from.
		LocationChange change = relocation();

		Assertions.assertThat(change.catalogFileId()).isEqualTo(10L);
		Assertions.assertThat(change.expectedCurrentPath()).isEqualTo(target);
		Assertions.assertThat(change.newPath()).isEqualTo(source);
		Assertions.assertThat(change.provenance().source()).isEqualTo(CatalogEventSources.ORGANIZATION);

		// The reversing operation settles as itself; the movement it reverses records
		// that its effect no longer stands, and nothing else about it is rewritten.
		Assertions.assertThat(change.eventId()).isEqualTo(reversalOf(10L).catalogFileEventPublicId());

		verify(movementWriter).markMoved(500L, List.of(reversalOf(10L).movementPublicId()));
		verify(movementWriter).markUndone(List.of(movement.getMovementPublicId()));
	}

	/**
	 * The reversal is a move in the opposite direction, prepared before the file is
	 * touched: it names the place the file is now as its source and the place it
	 * came from as its target, and carries the reason it exists at all.
	 */
	@Test
	void theReversalIsAnOperationOfItsOwnInTheOppositeDirection() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path source = sourceFolder.resolve("photo.jpg");
		Path target = Files.writeString(targetFolder.resolve("photo.jpg"), "content");

		Movement movement = movement(100L, catalogFile(10L, target), source, target, MovementStatus.MOVED);

		reversing();

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED)))
						.thenReturn(List.of(movement));

		undo(service(), 1L);

		ArgumentCaptor<List<MovementRequest>> requests = ArgumentCaptor.captor();

		verify(movementWriter).prepare(eq(500L), requests.capture());

		MovementRequest reversal = requests.getValue().getFirst();

		Assertions.assertThat(reversal.catalogFileId()).isEqualTo(10L);
		Assertions.assertThat(reversal.requestedSource()).isEqualTo(target);
		Assertions.assertThat(reversal.requestedTarget()).isEqualTo(source);
		Assertions.assertThat(reversal.reason()).isEqualTo(MovementReason.UNDONE_BY_USER);

		Assertions.assertThat(reversalOf(10L).movementPublicId())
				.as("the reversing operation is not the one being reversed")
				.isNotEqualTo(movement.getMovementPublicId());
	}

	@Test
	void undoRecreatesTheSourceDirectoryStructureThatOrganizationRemoved() throws Exception {
		Path sourceRoot = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		// The organization emptied and removed source/old/2018, so it no longer exists
		// on disk.
		Path source = sourceRoot.resolve("old").resolve("2018").resolve("photo.jpg");
		Path target = Files.writeString(targetFolder.resolve("photo.jpg"), "content");

		CatalogFile catalogFile = catalogFile(10L, target);

		Movement movement = movement(100L, catalogFile, source, target, MovementStatus.MOVED);

		reversing();

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED)))
						.thenReturn(List.of(movement));

		Assertions.assertThat(Files.exists(source.getParent())).isFalse();

		ExecutionCounts undo = undo(service(), 1L);

		Assertions.assertThat(undo.moved()).isEqualTo(1);
		// Moving the file back rebuilt the removed source folders (SecureFileMove
		// creates parents).
		Assertions.assertThat(Files.exists(source)).isTrue();
		Assertions.assertThat(Files.isDirectory(source.getParent())).isTrue();
		Assertions.assertThat(Files.isDirectory(sourceRoot.resolve("old"))).isTrue();
	}

	@Test
	void undoShouldContinueWhenOneMovementFails() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path missingSource = sourceFolder.resolve("missing.jpg");
		Path missingTarget = targetFolder.resolve("missing.jpg");
		Path okSource = sourceFolder.resolve("ok.jpg");
		Path okTarget = Files.writeString(targetFolder.resolve("ok.jpg"), "content");

		CatalogFile catalogFile = catalogFile(10L, okTarget);

		Movement missing = movement(100L, catalogFile(9L, missingTarget), missingSource, missingTarget,
				MovementStatus.MOVED);
		Movement ok = movement(101L, catalogFile, okSource, okTarget, MovementStatus.MOVED);

		reversing();

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED)))
						.thenReturn(List.of(missing, ok));

		ExecutionCounts undo = undo(service(), 1L);

		Assertions.assertThat(finishedStatus()).isEqualTo(ExecutionStatus.FINISHED_WITH_ERRORS);
		Assertions.assertThat(undo.moved()).isEqualTo(1);
		Assertions.assertThat(undo.failed()).isEqualTo(1);
		Assertions.assertThat(Files.exists(okSource)).isTrue();

		// The failure is the reversing operation's. The one it could not reverse is
		// left exactly as it was: it did move the file, which is still true.
		verify(movementWriter).markFailed(500L, List.of(reversalOf(9L).movementPublicId()),
				MovementReason.SOURCE_NOT_FOUND);
		verify(movementWriter, never()).markUndone(List.of(missing.getMovementPublicId()));
	}

	/**
	 * The physical restore already happened when the catalog write runs, so a
	 * movement with no media row, or one the write door refuses because the file is
	 * no longer where the reversal expected it, has to fail loudly enough to
	 * trigger the rollback rather than leave the catalog pointing at the old path.
	 */
	@Test
	void undoShouldFailTheMovementWhenTheCatalogSideCannotBeResolved() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));

		Path orphanSource = sourceFolder.resolve("orphan.jpg");
		Path orphanTarget = Files.writeString(targetFolder.resolve("orphan.jpg"), "content");

		Path refusedSource = sourceFolder.resolve("refused.jpg");
		Path refusedTarget = Files.writeString(targetFolder.resolve("refused.jpg"), "content");

		Movement withoutCatalog = Movement.builder().id(100L).execution(execution())
				.requestedSourcePath(orphanSource.toAbsolutePath().normalize().toString())
				.requestedTargetPath(orphanTarget.toAbsolutePath().normalize().toString())
				.status(MovementStatus.MOVED).build();

		Movement refused = movement(101L, catalogFile(20L, refusedTarget), refusedSource, refusedTarget,
				MovementStatus.MOVED);

		reversing();

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED)))
						.thenReturn(List.of(withoutCatalog, refused));
		doThrow(new IllegalStateException("the file is not where the reversal expected it"))
				.when(catalogLocationWriter).relocate(any());

		ExecutionCounts undo = undo(service(), 1L);

		Assertions.assertThat(undo.failed()).isEqualTo(2);
		Assertions.assertThat(undo.moved()).isZero();

		// A movement with no catalogued file reserves no reversal at all, so there is
		// nothing to close - only the run's error list records it. The refused one has
		// its reversal, and that is what fails.
		verify(movementWriter).markFailed(500L, List.of(reversalOf(20L).movementPublicId()), MovementReason.IO_ERROR);
		verify(movementWriter, never()).markUndone(any());
	}

	@Test
	void undoShouldSkipMovementAlreadyUndone() {
		Path source = tempDir.resolve("source/photo.jpg");
		Path target = tempDir.resolve("target/photo.jpg");

		Movement movement = movement(100L, catalogFile(10L, source), source, target, MovementStatus.UNDONE);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED)))
						.thenReturn(List.of(movement));

		ExecutionCounts undo = undo(service(), 1L);

		Assertions.assertThat(undo.skipped()).isEqualTo(1);
		Assertions.assertThat(undo.failed()).isZero();

		verify(catalogLocationWriter, never()).relocate(any());
	}

	@Test
	void undoShouldNotOverwriteOriginalPathWhenItAlreadyExists() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path source = Files.writeString(sourceFolder.resolve("photo.jpg"), "original");
		Path target = Files.writeString(targetFolder.resolve("photo.jpg"), "organized");

		Movement movement = movement(100L, catalogFile(10L, target), source, target, MovementStatus.MOVED);

		reversing();

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED)))
						.thenReturn(List.of(movement));

		ExecutionCounts undo = undo(service(), 1L);

		Assertions.assertThat(undo.failed()).isEqualTo(1);
		Assertions.assertThat(Files.readString(source)).isEqualTo("original");
		Assertions.assertThat(Files.readString(target)).isEqualTo("organized");

		verify(movementWriter).markFailed(500L, List.of(reversalOf(10L).movementPublicId()),
				MovementReason.TARGET_EXISTS);
		verify(movementWriter, never()).markUndone(any());
	}

	@Test
	void undoShouldReportErrorWhenTargetFileIsMissing() {
		Path source = tempDir.resolve("source/photo.jpg");
		Path target = tempDir.resolve("target/photo.jpg");

		Movement movement = movement(100L, catalogFile(10L, target), source, target, MovementStatus.MOVED);

		reversing();

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED)))
						.thenReturn(List.of(movement));

		ExecutionCounts undo = undo(service(), 1L);

		Assertions.assertThat(undo.failed()).isEqualTo(1);

		// The reason a movement could not be reversed used to travel back in the
		// response; it lives with every other per-file failure now, which is where
		// anyone looking at the run afterwards will find it.
		ArgumentCaptor<String> failure = ArgumentCaptor.forClass(String.class);

		verify(executionErrorService).save(any(), eq(ExecutionErrorType.MOVE_ERROR), failure.capture(), any());

		Assertions.assertThat(failure.getValue()).isEqualTo("Target file does not exist.");

		verify(movementWriter).markFailed(500L, List.of(reversalOf(10L).movementPublicId()),
				MovementReason.SOURCE_NOT_FOUND);
		verify(movementWriter, never()).markUndone(any());
	}

	@Test
	void undoRollsBackThePhysicalRestoreWhenPersistenceFailsKeepingDiskAndCatalogConsistent() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path source = sourceFolder.resolve("photo.jpg");
		Path target = Files.writeString(targetFolder.resolve("photo.jpg"), "content");

		CatalogFile catalogFile = catalogFile(10L, target);

		Movement movement = movement(100L, catalogFile, source, target, MovementStatus.MOVED);

		reversing();

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED)))
						.thenReturn(List.of(movement));
		doThrow(new IllegalStateException("database down")).when(catalogFileRepository).save(catalogFile);

		ExecutionCounts undo = undo(service(), 1L);

		Assertions.assertThat(finishedStatus()).isEqualTo(ExecutionStatus.FINISHED_WITH_ERRORS);
		Assertions.assertThat(undo.failed()).isEqualTo(1);
		// The DB update failed, so the physical restore is rolled back: the file
		// returns to the
		// target and the catalog (still pointing at target) stays consistent with disk.
		Assertions.assertThat(Files.exists(source)).isFalse();
		Assertions.assertThat(Files.exists(target)).isTrue();

		verify(movementWriter).markFailed(500L, List.of(reversalOf(10L).movementPublicId()), MovementReason.IO_ERROR);
		verify(movementWriter, never()).markUndone(any());

		// The reason an undo failed lives with every other per-file failure now.
		ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);

		verify(executionErrorService).save(any(), eq(ExecutionErrorType.MOVE_ERROR), message.capture(), any());

		Assertions.assertThat(message.getValue()).contains("database down");
	}

	private final ExecutionErrorService executionErrorService = mock(ExecutionErrorService.class);

	/**
	 * A reversal can run for minutes in another process now, so it has to be
	 * stoppable. It stops between files: everything already put back was put back
	 * under the locks and verified, and the rest simply does not start.
	 */
	@Test
	void stopsBetweenFilesWhenTheReversalIsCancelled() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path source = sourceFolder.resolve("photo.jpg");
		Path target = Files.writeString(targetFolder.resolve("photo.jpg"), "content");

		Movement movement = movement(100L, catalogFile(10L, target), source, target, MovementStatus.MOVED);

		reversing();

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED)))
						.thenReturn(List.of(movement));
		when(executionCancellationService.isCancelled(500L)).thenReturn(true);

		ExecutionCounts undo = undo(service(), 1L);

		Assertions.assertThat(finishedStatus()).isEqualTo(ExecutionStatus.CANCELLED);
		Assertions.assertThat(undo.moved()).isZero();
		Assertions.assertThat(Files.exists(target)).isTrue();
		Assertions.assertThat(Files.exists(source)).isFalse();
	}

	/**
	 * Losing the locks closes the commit, not the run that already happened: what
	 * went back is where it belongs, and nothing further is touched by a process
	 * that may no longer be entitled to write there.
	 */
	@Test
	void stopsBeforeTheNextFileWhenTheLocksUnderItAreGone() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path firstSource = sourceFolder.resolve("first.jpg");
		Path firstTarget = Files.writeString(targetFolder.resolve("first.jpg"), "first");
		Path secondSource = sourceFolder.resolve("second.jpg");
		Path secondTarget = Files.writeString(targetFolder.resolve("second.jpg"), "second");

		CatalogFile catalogFile = catalogFile(10L, firstTarget);

		Movement first = movement(100L, catalogFile, firstSource, firstTarget, MovementStatus.MOVED);
		Movement second = movement(101L, catalogFile(11L, secondTarget), secondSource, secondTarget,
				MovementStatus.MOVED);

		reversing();

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED)))
						.thenReturn(List.of(first, second));

		ExecutionOwnership lost = mock(ExecutionOwnership.class);

		Mockito.doNothing().doThrow(new OwnershipLostException("the session that held the locks is gone")).when(lost)
				.assertMayGoOnWorking();

		service().undo(1L, undoRow(), lost);

		Assertions.assertThat(finishedStatus()).isEqualTo(ExecutionStatus.INTERRUPTED);
		Assertions.assertThat(finishedCounts().moved()).isEqualTo(1);
		Assertions.assertThat(Files.exists(firstSource)).isTrue();
		Assertions.assertThat(Files.exists(secondTarget)).isTrue();
		Assertions.assertThat(Files.exists(secondSource)).isFalse();
	}

	/**
	 * A reversal that died halfway is never resumed - the handler is not resumable,
	 * so recovery closes the row and a second attempt is a fresh click. What that
	 * second run must not do is move a file that already went back.
	 *
	 * <p>
	 * The checkpoint is the movement's own status, written in the same transaction
	 * as the catalog and the location: either the file returned and the database
	 * knows, or neither happened. So the retry sees {@code UNDONE} and skips it
	 * without touching the disk, and only the movement still {@code MOVED} is
	 * reversed.
	 */
	@Test
	void aSecondAttemptSkipsWhatTheFirstAlreadyPutBack() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));

		Path alreadyBack = Files.writeString(sourceFolder.resolve("first.jpg"), "one");
		Path stillToGo = Files.writeString(targetFolder.resolve("second.jpg"), "two");

		CatalogFile done = catalogFile(10L, alreadyBack);
		CatalogFile pending = catalogFile(11L, stillToGo);

		Movement undone = movement(100L, done, sourceFolder.resolve("first.jpg"), targetFolder.resolve("first.jpg"),
				MovementStatus.UNDONE);
		Movement moved = movement(101L, pending, sourceFolder.resolve("second.jpg"), stillToGo, MovementStatus.MOVED);

		reversing();

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED)))
						.thenReturn(List.of(undone, moved));

		ExecutionCounts undo = undo(service(), 1L);

		Assertions.assertThat(undo.moved()).isEqualTo(1);
		Assertions.assertThat(undo.skipped()).isEqualTo(1);
		Assertions.assertThat(undo.failed()).isZero();

		// The file that had already returned was neither moved again nor disturbed,
		// and no second movement row was written for it.
		Assertions.assertThat(Files.readString(alreadyBack)).isEqualTo("one");
		Assertions.assertThat(Files.exists(targetFolder.resolve("first.jpg"))).isFalse();

		Assertions.assertThat(Files.exists(sourceFolder.resolve("second.jpg"))).isTrue();
		Assertions.assertThat(Files.exists(stillToGo)).isFalse();

		// Only the one that still had to go back is closed, and the one the first
		// attempt already reversed is not touched a second time.
		verify(movementWriter).markUndone(List.of(moved.getMovementPublicId()));
		verify(movementWriter, never()).markUndone(List.of(undone.getMovementPublicId()));
	}

	/**
	 * Undoing a quarantine puts files back in the set a duplicate analysis looks
	 * at - they were DELETED and are ACTIVE again - so the answer has to be brought
	 * up to date whatever the folders involved were. Said once for the reversal,
	 * not once per file.
	 */
	@Test
	void undoingAQuarantineAsksForOneRegroup() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path source = sourceFolder.resolve("photo.jpg");
		Path target = Files.writeString(targetFolder.resolve("photo.jpg"), "content");

		CatalogFile catalogFile = catalogFile(10L, target);

		catalogFile.setLifecycleStatus(LifecycleStatus.DELETED);

		Movement movement = movement(100L, catalogFile, source, target, MovementStatus.MOVED);

		movement.setReason(MovementReason.DUPLICATE_QUARANTINED);

		reversing();

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED)))
						.thenReturn(List.of(movement));

		Assertions.assertThat(undo(service(), 1L).moved()).isEqualTo(1);

		// The file is in the analysed set again because it is ACTIVE again, and what
		// explains that in the history is the reversing operation's own move - no
		// separate lifecycle write, and no second fact.
		Assertions.assertThat(catalogFile.getLifecycleStatus()).isEqualTo(LifecycleStatus.ACTIVE);

		verify(eligibilityAnnouncer).announce("organization undo");
	}

	/**
	 * The reversal of an ordinary organization move carries no reason and changes
	 * no lifecycle: files go back to a folder, and whether that matters is a
	 * question for the exclusion list, which here has nothing to say.
	 */
	@Test
	void undoingAnOrganizationMoveNoExclusionCaresAboutAsksForNothing() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path source = sourceFolder.resolve("photo.jpg");
		Path target = Files.writeString(targetFolder.resolve("photo.jpg"), "content");

		CatalogFile catalogFile = catalogFile(10L, target);

		reversing();

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED))).thenReturn(
						List.of(movement(100L, catalogFile, source, target, MovementStatus.MOVED)));

		Assertions.assertThat(undo(service(), 1L).moved()).isEqualTo(1);

		verify(eligibilityAnnouncer, never()).announce(any());
	}

	private ExecutionCounts undo(OrganizationUndoService service, long undoneExecutionId) {
		service.undo(undoneExecutionId, undoRow(), owning());

		return finishedCounts();
	}

	/**
	 * What the reversal reported when it closed the row. Read from the call rather
	 * than from the entity: the row is written by the shared finisher now, under
	 * the taking the undo carries, and this service no longer touches it.
	 */
	private ExecutionCounts finishedCounts() {
		ArgumentCaptor<ExecutionCounts> counts = ArgumentCaptor.forClass(ExecutionCounts.class);

		verify(executionProgressService).finishCommand(any(), any(), counts.capture(), any());

		return counts.getValue();
	}

	private ExecutionStatus finishedStatus() {
		ArgumentCaptor<ExecutionStatus> status = ArgumentCaptor.forClass(ExecutionStatus.class);

		verify(executionProgressService).finishCommand(any(), status.capture(), any(), any());

		return status.getValue();
	}

	private Execution undoRow() {
		return Execution.builder().id(500L).executionType(ExecutionType.UNDO).status(ExecutionStatus.RUNNING).build();
	}

	/**
	 * The taking every write about the row is made under. A real one rather than a
	 * stub, so a write refused for having lost its turn is refused here too.
	 */
	private final ExecutionOwnership ownership = Takings.owning(1L);

	private ExecutionOwnership owning() {
		return ownership;
	}

	private void stubUndoExecution() {
		lenient().when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	private OrganizationUndoService service() {
		stubUndoExecution();

		WorkspaceManager workspace = mock(WorkspaceManager.class);

		when(workspace.getWorkspacePath()).thenReturn(tempDir);

		return new OrganizationUndoService(executionRepository, catalogFileRepository, catalogLocationWriter,
				contentReconciliation,
				new OrganizationMovementLog(movementRepository, movementWriter, executionErrorService),
				operationLockService, new OrganizationPathValidator(mock(AppSettingService.class), workspace),
				executionProgressService, executionCancellationService,
				new SecureLibraryFiles(
						new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()), pathRegistry),
						pathRegistry),
				eligibilityAnnouncer, mock(PlatformTransactionManager.class), Clock.systemDefaultZone());
	}

	@Test
	void undoLocksTheOriginalRestorePathsNotOnlyTheExecutionRoot() {
		// A DEDUP_DELETE execution's source/target are both the quarantine root, yet
		// each file is restored to its ORIGINAL path (movement.sourcePath), which lies
		// outside the root. The lock must cover those paths too, or a concurrent
		// organization on the original tree would race the restore.
		Path quarantineRoot = tempDir.resolve("trash");
		Path original = tempDir.resolve("library").resolve("photo.jpg");
		Path quarantineCopy = quarantineRoot.resolve("exec-1").resolve("10__photo.jpg");

		Execution dedup = Execution.builder().id(1L).executionType(ExecutionType.DEDUP_DELETE)
				.status(ExecutionStatus.FINISHED).sourcePath(quarantineRoot.toString())
				.targetPath(quarantineRoot.toString()).build();

		CatalogFile catalogFile = catalogFile(10L, quarantineCopy);

		Movement movement = Movement.builder().id(100L).execution(dedup).catalogFile(catalogFile)
				.requestedSourcePath(original.toAbsolutePath().normalize().toString())
				.requestedTargetPath(quarantineCopy.toAbsolutePath().normalize().toString()).status(MovementStatus.MOVED)
				.build();

		OperationLockService lockService = mock(OperationLockService.class);

		when(lockService.acquire(eq(ExecutionType.UNDO), any(Path[].class))).thenReturn(mock(OperationLock.class));

		stubUndoExecution();

		OrganizationUndoService service = new OrganizationUndoService(executionRepository, catalogFileRepository,
				catalogLocationWriter, contentReconciliation,
				new OrganizationMovementLog(movementRepository, movementWriter, executionErrorService),
				lockService, mock(OrganizationPathValidator.class), executionProgressService,
				executionCancellationService,
				new SecureLibraryFiles(
						new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()), pathRegistry),
						pathRegistry),
				eligibilityAnnouncer, mock(PlatformTransactionManager.class), Clock.systemDefaultZone());

		when(executionRepository.findById(1L)).thenReturn(Optional.of(dedup));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED)))
						.thenReturn(List.of(movement));

		undo(service, 1L);

		ArgumentCaptor<Path[]> lockedPaths = ArgumentCaptor.forClass(Path[].class);

		verify(lockService).acquire(eq(ExecutionType.UNDO), lockedPaths.capture());

		Assertions.assertThat(lockedPaths.getValue())
				.contains(PathUtils.normalizePath(original.toAbsolutePath().normalize().toString()));
	}

	@Test
	void undoShouldRejectAnExecutionThatDoesNotExist() {
		when(executionRepository.findById(99L)).thenReturn(Optional.empty());

		OrganizationUndoService service = serviceWithoutWorkspace();

		Execution undoExecution = undoRow();

		Assertions.assertThatThrownBy(() -> service.undo(99L, undoExecution, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Execution not found: 99");

		verify(movementRepository, never()).findByExecutionIdAndStatusInOrderByIdDesc(any(), any());
	}

	/**
	 * A crash mid-loop must not leave the row open: an execution still holding a
	 * null {@code finishedAt} is read everywhere as the operation currently
	 * running, so it would show a phantom undo on every screen.
	 */
	@Test
	void aCrashMidUndoStillClosesTheExecution() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path source = sourceFolder.resolve("photo.jpg");
		Path target = Files.writeString(targetFolder.resolve("photo.jpg"), "content");

		CatalogFile catalogFile = catalogFile(10L, target);

		Movement movement = movement(100L, catalogFile, source, target, MovementStatus.MOVED);

		Execution undoExecution = Execution.builder().id(2L).build();

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED)))
						.thenReturn(List.of(movement));

		OrganizationPathValidator refusingValidator = mock(OrganizationPathValidator.class);

		doThrow(new IllegalStateException("outside the workspace")).when(refusingValidator).validateAllowed(any(),
				any());

		stubUndoExecution();

		OrganizationUndoService service = new OrganizationUndoService(executionRepository, catalogFileRepository,
				catalogLocationWriter, contentReconciliation,
				new OrganizationMovementLog(movementRepository, movementWriter, executionErrorService),
				operationLockService, refusingValidator, executionProgressService, executionCancellationService,
				new SecureLibraryFiles(
						new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()), pathRegistry),
						pathRegistry),
				eligibilityAnnouncer, mock(PlatformTransactionManager.class), Clock.systemDefaultZone());

		// The failure is written to the row rather than thrown at the worker: the
		// dispatcher's generic outcome would say nothing about which undo it was.
		service.undo(1L, undoExecution, owning());

		verify(executionProgressService).fail(ownership, OrganizationMessages.undoFailed("outside the workspace"));
	}

	/**
	 * The validation guards bail out before any path is resolved, so this variant
	 * skips the workspace stubbing that would otherwise go unused.
	 */
	private OrganizationUndoService serviceWithoutWorkspace() {
		stubUndoExecution();

		return new OrganizationUndoService(executionRepository, catalogFileRepository, catalogLocationWriter,
				contentReconciliation,
				new OrganizationMovementLog(movementRepository, movementWriter, executionErrorService),
				operationLockService, mock(OrganizationPathValidator.class), executionProgressService,
				executionCancellationService,
				new SecureLibraryFiles(
						new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()), pathRegistry),
						pathRegistry),
				eligibilityAnnouncer, mock(PlatformTransactionManager.class), Clock.systemDefaultZone());
	}

	private Execution execution() {
		return Execution.builder().id(1L).executionType(ExecutionType.ORGANIZATION).status(ExecutionStatus.FINISHED)
				.sourcePath(tempDir.resolve("source").toString()).targetPath(tempDir.resolve("target").toString())
				.build();
	}

	/**
	 * The placement as the row now holds it, which the reversed entry has to agree
	 * with - the save that follows is a merge, and would otherwise put the file
	 * back at the place the undo just took it from.
	 */
	@BeforeEach
	void thePlacementDoorAnswers() {
		lenient().when(catalogLocationWriter.relocate(any()))
				.thenAnswer(invocation -> AppliedLocationChanges.applying(invocation.getArgument(0)));
	}

	private CatalogFile catalogFile(Long id, Path path) {
		return CatalogFiles.located(CatalogFile.builder().id(id).extension("jpg").sizeBytes(10L)
				.modifiedAt(Instant.EPOCH).importedAt(Instant.EPOCH).fileType(FileType.PHOTO)
				.lifecycleStatus(LifecycleStatus.ACTIVE).build(), path);
	}

	/**
	 * A movement with its own identity, because that is what an undo settles and
	 * what it marks as no longer standing - the row's primary key names it for the
	 * database, its public id names it for everything else.
	 */
	private Movement movement(Long id, CatalogFile catalogFile, Path source, Path target, MovementStatus status) {
		return Movement.builder().id(id).movementPublicId(UuidV7.generate()).execution(execution())
				.catalogFile(catalogFile).requestedSourcePath(source.toAbsolutePath().normalize().toString())
				.requestedTargetPath(target.toAbsolutePath().normalize().toString()).status(status).build();
	}

	/**
	 * The reversing operations the run reserves before it touches a file, minted
	 * from the requests themselves so a test asserts on the identities the run
	 * actually decided on rather than on ones it invented afterwards.
	 */
	private void reversing() {
		lenient().when(movementWriter.prepare(eq(500L), anyList())).thenAnswer(invocation -> {
			List<MovementRequest> requests = invocation.getArgument(1);

			List<PreparedMovement> prepared = new ArrayList<>();

			for (MovementRequest request : requests) {
				PreparedMovement reversal = PreparedMovements.pending(reserved.size() + 1L, request.catalogFileId(),
						request.requestedSource(), request.requestedTarget());

				reserved.put(request.catalogFileId(), reversal);

				prepared.add(reversal);
			}

			return prepared;
		});
	}

	private PreparedMovement reversalOf(long catalogFileId) {
		return reserved.get(catalogFileId);
	}

	/** The one change the run stated to the write door. */
	private LocationChange relocation() {
		ArgumentCaptor<LocationChange> change = ArgumentCaptor.forClass(LocationChange.class);

		verify(catalogLocationWriter).relocate(change.capture());

		return change.getValue();
	}
}