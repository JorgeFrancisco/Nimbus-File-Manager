package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import br.com.jorgemelo.nimbusfilemanager.execution.application.GrantingOperationLocks;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.constants.OrganizationMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLock;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OwnershipLostException;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.InMemorySelfWrittenPaths;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

@ExtendWith(MockitoExtension.class)
class OrganizationUndoServiceTest {

	private final SelfWrittenPathRegistry pathRegistry = new SelfWrittenPathRegistry(new InMemorySelfWrittenPaths(),
			Clock.systemDefaultZone());

	@TempDir
	Path tempDir;

	@Mock
	private EligibilityAnnouncer eligibilityAnnouncer;

	@Mock
	private ExecutionRepository executionRepository;

	@Mock
	private CatalogFileRepository catalogFileRepository;

	@Mock
	private CatalogFileLocationRepository catalogFileLocationRepository;

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

		CatalogFileLocation location = location(catalogFile, target);

		Movement movement = movement(100L, catalogFile, source, target, MovementStatus.MOVED);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
						.thenReturn(List.of(movement));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(10L,
				target.toAbsolutePath().normalize().toString())).thenReturn(Optional.of(location));

		ExecutionCounts undo = undo(service(), 1L);

		Assertions.assertThat(finishedStatus()).isEqualTo(ExecutionStatus.FINISHED);
		Assertions.assertThat(undo.moved()).isEqualTo(1);
		Assertions.assertThat(undo.failed()).isZero();
		Assertions.assertThat(Files.exists(source)).isTrue();
		Assertions.assertThat(Files.exists(target)).isFalse();
		Assertions.assertThat(catalogFile.getFileKey()).isEqualTo(source.toAbsolutePath().normalize().toString());
		Assertions.assertThat(location.getCurrentPath()).isEqualTo(source.toAbsolutePath().normalize().toString());
		Assertions.assertThat(movement.getStatus()).isEqualTo(MovementStatus.UNDONE);

		// The reversal is a movement of its own, in the opposite direction: two saves,
		// the mark on the original and the row the undo appends.
		ArgumentCaptor<Movement> saved = ArgumentCaptor.forClass(Movement.class);

		verify(movementRepository, times(2)).save(saved.capture());

		Movement reverse = saved.getAllValues().get(1);

		Assertions.assertThat(reverse.getReason()).isEqualTo(MovementReason.UNDONE_BY_USER);
		Assertions.assertThat(reverse.getSourcePath()).isEqualTo(movement.getTargetPath());
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

		CatalogFileLocation location = location(catalogFile, target);

		Movement movement = movement(100L, catalogFile, source, target, MovementStatus.MOVED);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
						.thenReturn(List.of(movement));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(10L,
				target.toAbsolutePath().normalize().toString())).thenReturn(Optional.of(location));

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

		CatalogFileLocation location = location(catalogFile, okTarget);

		Movement missing = movement(100L, catalogFile(9L, missingTarget), missingSource, missingTarget,
				MovementStatus.MOVED);
		Movement ok = movement(101L, catalogFile, okSource, okTarget, MovementStatus.MOVED);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
						.thenReturn(List.of(missing, ok));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(10L,
				okTarget.toAbsolutePath().normalize().toString())).thenReturn(Optional.of(location));

		ExecutionCounts undo = undo(service(), 1L);

		Assertions.assertThat(finishedStatus()).isEqualTo(ExecutionStatus.FINISHED_WITH_ERRORS);
		Assertions.assertThat(undo.moved()).isEqualTo(1);
		Assertions.assertThat(undo.failed()).isEqualTo(1);
		Assertions.assertThat(missing.getStatus()).isEqualTo(MovementStatus.UNDO_ERROR);
		Assertions.assertThat(missing.getReason()).isEqualTo(MovementReason.SOURCE_NOT_FOUND);
		Assertions.assertThat(Files.exists(okSource)).isTrue();
	}

	/**
	 * The physical restore already happened when the catalog write runs, so a
	 * movement with no media row, or one whose placement no longer matches, has to
	 * fail loudly enough to trigger the rollback rather than leave the catalog
	 * pointing at the old path.
	 */
	@Test
	void undoShouldFailTheMovementWhenTheCatalogSideCannotBeResolved() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));

		Path orphanSource = sourceFolder.resolve("orphan.jpg");
		Path orphanTarget = Files.writeString(targetFolder.resolve("orphan.jpg"), "content");

		Path unlinkedSource = sourceFolder.resolve("unlinked.jpg");
		Path unlinkedTarget = Files.writeString(targetFolder.resolve("unlinked.jpg"), "content");

		Movement withoutCatalog = Movement.builder().id(100L).execution(execution())
				.sourcePath(orphanSource.toAbsolutePath().normalize().toString())
				.targetPath(orphanTarget.toAbsolutePath().normalize().toString()).status(MovementStatus.MOVED).build();

		Movement withoutLocation = movement(101L, catalogFile(20L, unlinkedTarget), unlinkedSource, unlinkedTarget,
				MovementStatus.MOVED);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
						.thenReturn(List.of(withoutCatalog, withoutLocation));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(eq(20L), any()))
				.thenReturn(Optional.empty());

		ExecutionCounts undo = undo(service(), 1L);

		Assertions.assertThat(undo.failed()).isEqualTo(2);
		Assertions.assertThat(undo.moved()).isZero();
		Assertions.assertThat(withoutCatalog.getStatus()).isEqualTo(MovementStatus.UNDO_ERROR);
		Assertions.assertThat(withoutLocation.getStatus()).isEqualTo(MovementStatus.UNDO_ERROR);
	}

	@Test
	void undoShouldSkipMovementAlreadyUndone() {
		Path source = tempDir.resolve("source/photo.jpg");
		Path target = tempDir.resolve("target/photo.jpg");

		Movement movement = movement(100L, catalogFile(10L, source), source, target, MovementStatus.UNDONE);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
						.thenReturn(List.of(movement));

		ExecutionCounts undo = undo(service(), 1L);

		Assertions.assertThat(undo.skipped()).isEqualTo(1);
		Assertions.assertThat(undo.failed()).isZero();

		verify(catalogFileLocationRepository, never()).save(any());
	}

	@Test
	void undoShouldNotOverwriteOriginalPathWhenItAlreadyExists() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path source = Files.writeString(sourceFolder.resolve("photo.jpg"), "original");
		Path target = Files.writeString(targetFolder.resolve("photo.jpg"), "organized");

		Movement movement = movement(100L, catalogFile(10L, target), source, target, MovementStatus.MOVED);

		ArgumentCaptor<Movement> captor = ArgumentCaptor.forClass(Movement.class);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
						.thenReturn(List.of(movement));

		ExecutionCounts undo = undo(service(), 1L);

		Assertions.assertThat(undo.failed()).isEqualTo(1);
		Assertions.assertThat(Files.readString(source)).isEqualTo("original");
		Assertions.assertThat(Files.readString(target)).isEqualTo("organized");

		verify(movementRepository).save(captor.capture());

		Assertions.assertThat(captor.getValue().getStatus()).isEqualTo(MovementStatus.UNDO_ERROR);
		Assertions.assertThat(captor.getValue().getReason()).isEqualTo(MovementReason.TARGET_EXISTS);
	}

	@Test
	void undoShouldReportErrorWhenTargetFileIsMissing() {
		Path source = tempDir.resolve("source/photo.jpg");
		Path target = tempDir.resolve("target/photo.jpg");

		Movement movement = movement(100L, catalogFile(10L, target), source, target, MovementStatus.MOVED);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
						.thenReturn(List.of(movement));

		ExecutionCounts undo = undo(service(), 1L);

		Assertions.assertThat(undo.failed()).isEqualTo(1);

		// The reason a movement could not be reversed used to travel back in the
		// response; it lives with every other per-file failure now, which is where
		// anyone looking at the run afterwards will find it.
		ArgumentCaptor<String> failure = ArgumentCaptor.forClass(String.class);

		verify(executionErrorService).save(any(), eq(ExecutionErrorType.MOVE_ERROR), failure.capture(), any());

		Assertions.assertThat(failure.getValue()).isEqualTo("Target file does not exist.");
		Assertions.assertThat(movement.getStatus()).isEqualTo(MovementStatus.UNDO_ERROR);
		Assertions.assertThat(movement.getReason()).isEqualTo(MovementReason.SOURCE_NOT_FOUND);
	}

	@Test
	void undoRollsBackThePhysicalRestoreWhenPersistenceFailsKeepingDiskAndCatalogConsistent() throws Exception {
		Path sourceFolder = Files.createDirectory(tempDir.resolve("source"));
		Path targetFolder = Files.createDirectory(tempDir.resolve("target"));
		Path source = sourceFolder.resolve("photo.jpg");
		Path target = Files.writeString(targetFolder.resolve("photo.jpg"), "content");

		CatalogFile catalogFile = catalogFile(10L, target);

		CatalogFileLocation location = location(catalogFile, target);

		Movement movement = movement(100L, catalogFile, source, target, MovementStatus.MOVED);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
						.thenReturn(List.of(movement));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(10L,
				target.toAbsolutePath().normalize().toString())).thenReturn(Optional.of(location));
		doThrow(new IllegalStateException("database down")).when(catalogFileRepository).save(catalogFile);

		ExecutionCounts undo = undo(service(), 1L);

		Assertions.assertThat(finishedStatus()).isEqualTo(ExecutionStatus.FINISHED_WITH_ERRORS);
		Assertions.assertThat(undo.failed()).isEqualTo(1);
		// The DB update failed, so the physical restore is rolled back: the file
		// returns to the
		// target and the catalog (still pointing at target) stays consistent with disk.
		Assertions.assertThat(Files.exists(source)).isFalse();
		Assertions.assertThat(Files.exists(target)).isTrue();
		Assertions.assertThat(movement.getStatus()).isEqualTo(MovementStatus.UNDO_ERROR);
		Assertions.assertThat(movement.getReason()).isEqualTo(MovementReason.IO_ERROR);

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

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
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

		CatalogFileLocation location = location(catalogFile, firstTarget);

		Movement first = movement(100L, catalogFile, firstSource, firstTarget, MovementStatus.MOVED);
		Movement second = movement(101L, catalogFile(11L, secondTarget), secondSource, secondTarget,
				MovementStatus.MOVED);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
						.thenReturn(List.of(first, second));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(10L,
				firstTarget.toAbsolutePath().normalize().toString())).thenReturn(Optional.of(location));

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

		CatalogFileLocation location = location(pending, stillToGo);

		Movement undone = movement(100L, done, sourceFolder.resolve("first.jpg"), targetFolder.resolve("first.jpg"),
				MovementStatus.UNDONE);
		Movement moved = movement(101L, pending, sourceFolder.resolve("second.jpg"), stillToGo, MovementStatus.MOVED);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
						.thenReturn(List.of(undone, moved));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(11L,
				stillToGo.toAbsolutePath().normalize().toString())).thenReturn(Optional.of(location));

		ExecutionCounts undo = undo(service(), 1L);

		Assertions.assertThat(undo.moved()).isEqualTo(1);
		Assertions.assertThat(undo.skipped()).isEqualTo(1);
		Assertions.assertThat(undo.failed()).isZero();

		// The file that had already returned was neither moved again nor disturbed,
		// and no second movement row was written for it.
		Assertions.assertThat(Files.readString(alreadyBack)).isEqualTo("one");
		Assertions.assertThat(Files.exists(targetFolder.resolve("first.jpg"))).isFalse();
		Assertions.assertThat(undone.getStatus()).isEqualTo(MovementStatus.UNDONE);

		Assertions.assertThat(Files.exists(sourceFolder.resolve("second.jpg"))).isTrue();
		Assertions.assertThat(Files.exists(stillToGo)).isFalse();
		Assertions.assertThat(moved.getStatus()).isEqualTo(MovementStatus.UNDONE);
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

		CatalogFileLocation location = location(catalogFile, target);

		Movement movement = movement(100L, catalogFile, source, target, MovementStatus.MOVED);

		movement.setReason(MovementReason.DUPLICATE_QUARANTINED);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
						.thenReturn(List.of(movement));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(10L,
				target.toAbsolutePath().normalize().toString())).thenReturn(Optional.of(location));

		Assertions.assertThat(undo(service(), 1L).moved()).isEqualTo(1);

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

		CatalogFileLocation location = location(catalogFile, target);

		when(executionRepository.findById(1L)).thenReturn(Optional.of(execution()));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR))).thenReturn(
						List.of(movement(100L, catalogFile, source, target, MovementStatus.MOVED)));
		when(catalogFileLocationRepository.findByCatalogFileIdAndCurrentPath(10L,
				target.toAbsolutePath().normalize().toString())).thenReturn(Optional.of(location));

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

		return new OrganizationUndoService(executionRepository, catalogFileRepository, catalogFileLocationRepository,
				new OrganizationMovementLog(movementRepository, catalogFileRepository, executionErrorService),
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
				.sourcePath(original.toAbsolutePath().normalize().toString())
				.targetPath(quarantineCopy.toAbsolutePath().normalize().toString()).status(MovementStatus.MOVED)
				.build();

		OperationLockService lockService = mock(OperationLockService.class);

		when(lockService.acquire(eq(ExecutionType.UNDO), any(Path[].class))).thenReturn(mock(OperationLock.class));

		stubUndoExecution();

		OrganizationUndoService service = new OrganizationUndoService(executionRepository, catalogFileRepository,
				catalogFileLocationRepository,
				new OrganizationMovementLog(movementRepository, catalogFileRepository, executionErrorService),
				lockService, mock(OrganizationPathValidator.class), executionProgressService,
				executionCancellationService,
				new SecureLibraryFiles(
						new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()), pathRegistry),
						pathRegistry),
				eligibilityAnnouncer, mock(PlatformTransactionManager.class), Clock.systemDefaultZone());

		when(executionRepository.findById(1L)).thenReturn(Optional.of(dedup));
		when(movementRepository.findByExecutionIdAndStatusInOrderByIdDesc(1L,
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
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
				List.of(MovementStatus.MOVED, MovementStatus.UNDONE, MovementStatus.UNDO_ERROR)))
						.thenReturn(List.of(movement));

		OrganizationPathValidator refusingValidator = mock(OrganizationPathValidator.class);

		doThrow(new IllegalStateException("outside the workspace")).when(refusingValidator).validateAllowed(any(),
				any());

		stubUndoExecution();

		OrganizationUndoService service = new OrganizationUndoService(executionRepository, catalogFileRepository,
				catalogFileLocationRepository,
				new OrganizationMovementLog(movementRepository, catalogFileRepository, executionErrorService),
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

		return new OrganizationUndoService(executionRepository, catalogFileRepository, catalogFileLocationRepository,
				new OrganizationMovementLog(movementRepository, catalogFileRepository, executionErrorService),
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

	private CatalogFile catalogFile(Long id, Path path) {
		return CatalogFile.builder().id(id).fileKey(path.toAbsolutePath().normalize().toString())
				.fileName(path.getFileName().toString()).extension("jpg").sizeBytes(10L).modifiedAt(LocalDateTime.now())
				.importedAt(LocalDateTime.now()).fileType(FileType.PHOTO).lifecycleStatus(LifecycleStatus.ACTIVE)
				.build();
	}

	private CatalogFileLocation location(CatalogFile catalogFile, Path path) {
		return CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath(path.toAbsolutePath().normalize().toString())
				.currentFolder(path.getParent().toAbsolutePath().normalize().toString())
				.originalPath(path.toAbsolutePath().normalize().toString())
				.originalFolder(path.getParent().toAbsolutePath().normalize().toString()).updatedAt(LocalDateTime.now())
				.build();
	}

	private Movement movement(Long id, CatalogFile catalogFile, Path source, Path target, MovementStatus status) {
		return Movement.builder().id(id).execution(execution()).catalogFile(catalogFile)
				.sourcePath(source.toAbsolutePath().normalize().toString())
				.targetPath(target.toAbsolutePath().normalize().toString()).status(status).build();
	}
}