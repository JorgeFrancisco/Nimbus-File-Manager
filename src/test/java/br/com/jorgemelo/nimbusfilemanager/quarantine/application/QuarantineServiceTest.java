package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionStopReason;
import br.com.jorgemelo.nimbusfilemanager.execution.application.GrantingOperationLocks;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OwnershipLostException;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.MoveIntegrityException;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationMoveVerifier;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureFileMove;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureLibraryFiles;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreBatchResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.RestoreOutcome;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.PreparedMovements;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWriteOff;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.MovementWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * The moving half of a restore, which is all this class is now: it runs under a
 * row a worker claimed, and every question a person could have been asked was
 * already answered before the request was queued.
 *
 * <p>
 * What it can still find is that the world moved in between - the destination
 * taken, the quarantine copy gone, the path held by another operation - and
 * those are outcomes, not questions. The conversation that produces the
 * destination is {@link QuarantineRestorePlanner}, tested apart.
 */
class QuarantineServiceTest {

	private final SelfWrittenPathRegistry pathRegistry = new SelfWriteOff();
	private final MovementRepository movementRepository = mock(MovementRepository.class);
	private final QuarantinePersistence persistence = mock(QuarantinePersistence.class);
	private final ExecutionCancellationService executionCancellationService = mock(ExecutionCancellationService.class);
	private final ExecutionStopReason executionStopReason = new ExecutionStopReason(executionCancellationService);
	private final QuarantineOperationLog restoreLog = mock(QuarantineOperationLog.class);
	/** Inert on purpose: what it is told is asserted where it is consumed. */
	private final EligibilityAnnouncer eligibilityAnnouncer = mock(EligibilityAnnouncer.class);

	private final MovementWriter movementWriter = mock(MovementWriter.class);

	/** Distinct per file, so a batch of several never collides on one key. */
	private long nextCatalogFileId = 10L;

	private final QuarantineService service = new QuarantineService(movementRepository, movementWriter, new FileHashService(), persistence,
			new SecureLibraryFiles(new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()),
					pathRegistry), pathRegistry),
			GrantingOperationLocks.granting(), executionStopReason, restoreLog, eligibilityAnnouncer);

	/**
	 * The operations the write door hands back, one per file the batch decided to
	 * restore. A batch whose files are all refused before that point never asks,
	 * and the door answering nothing is what a refusal looks like from here.
	 */
	@BeforeEach
	void theWriteDoorAnswers() {
		when(movementWriter.prepare(anyLong(), anyList()))
				.thenAnswer(invocation -> PreparedMovements.pendingFor(invocation.getArgument(1)));
	}

	@Test
	void restoresEachSelectedFileBackToItsOwnOrigin(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(movement.getMovementPublicId()), null, claimedRow(),
				owning());

		Assertions.assertThat(result.total()).isEqualTo(1);
		Assertions.assertThat(result.restored()).isEqualTo(1);
		Assertions.assertThat(Files.exists(origin.resolve("a.jpg"))).isTrue();
		Assertions.assertThat(Files.exists(quarantine)).isFalse();

		verify(persistence).persistRestore(anyLong(), any(), any(), any(), any(), any());
	}

	/**
	 * The single restore arrives with its destination already settled - including
	 * the new name somebody chose for a collision - and the worker writes exactly
	 * that, without deciding anything on their behalf.
	 */
	@Test
	void restoresToTheDestinationThatWasDecided(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		Path decided = elsewhere.resolve("a (1).jpg");

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(movement.getMovementPublicId()), decided,
				claimedRow(), owning());

		Assertions.assertThat(result.restored()).isEqualTo(1);
		Assertions.assertThat(result.items().get(0).restoredPath()).isEqualTo(PathUtils.normalize(decided));
		Assertions.assertThat(Files.exists(decided)).isTrue();
		Assertions.assertThat(Files.exists(origin.resolve("a.jpg"))).isFalse();
	}

	@Test
	void countsRestoredAndConflictsApart(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path freeOriginal = origin.resolve("free.jpg");
		Path takenOriginal = Files.writeString(origin.resolve("taken.jpg"), "existing");

		Path freeQuarantine = writeQuarantineCopy(tmp, "10__free.jpg", "one");
		Path takenQuarantine = writeQuarantineCopy(tmp, "11__taken.jpg", "two");

		Movement free = quarantineMovement(freeOriginal, freeQuarantine);
		Movement taken = quarantineMovement(takenOriginal, takenQuarantine);

		when(movementRepository.findByMovementPublicId(free.getMovementPublicId())).thenReturn(Optional.of(free));
		when(movementRepository.findByMovementPublicId(taken.getMovementPublicId())).thenReturn(Optional.of(taken));

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(free.getMovementPublicId(), taken.getMovementPublicId()),
				null, claimedRow(), owning());

		Assertions.assertThat(result.total()).isEqualTo(2);
		Assertions.assertThat(result.restored()).isEqualTo(1);
		Assertions.assertThat(result.conflicts()).isEqualTo(1);
		Assertions.assertThat(Files.exists(freeOriginal)).isTrue();
		Assertions.assertThat(Files.readString(takenOriginal)).isEqualTo("existing");
		Assertions.assertThat(Files.exists(takenQuarantine)).isTrue();
	}

	/**
	 * A destination that was free when the conversation happened and is taken by
	 * the time the worker gets there: the file stays in quarantine and the person
	 * is told, which starts the same conversation again rather than overwriting.
	 */
	@Test
	void keepsTheFileWhenTheDecidedDestinationWasTakenInTheMeantime(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path taken = Files.writeString(origin.resolve("a.jpg"), "somebody else's");
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(movement.getMovementPublicId()), taken, claimedRow(),
				owning());

		Assertions.assertThat(result.conflicts()).isEqualTo(1);
		Assertions.assertThat(Files.readString(taken)).isEqualTo("somebody else's");
		Assertions.assertThat(Files.exists(quarantine)).isTrue();
	}

	@Test
	void reportsOriginMissingWhenTheOriginalFolderIsGone(@TempDir Path tmp) throws Exception {
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(tmp.resolve("gone").resolve("a.jpg"), quarantine);

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(movement.getMovementPublicId()), null, claimedRow(),
				owning());

		Assertions.assertThat(result.originMissing()).isEqualTo(1);
		Assertions.assertThat(Files.exists(quarantine)).isTrue();

		verify(persistence, never()).persistRestore(anyLong(), any(), any(), any(), any(), any());
	}

	/**
	 * A recorded original path with no folder above it - a row that lost its
	 * directory somewhere between two versions - would restore into nowhere.
	 */
	@Test
	void refusesToRestoreWhenTheRecordedOriginalPathHasNoFolder(@TempDir Path tmp) throws Exception {
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(tmp.getRoot(), quarantine);

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(movement.getMovementPublicId()), null, claimedRow(),
				owning());

		Assertions.assertThat(result.errors()).isEqualTo(1);
		Assertions.assertThat(result.items().get(0).message()).isNotBlank();
		Assertions.assertThat(Files.exists(quarantine)).isTrue();

		verify(persistence, never()).persistRestore(anyLong(), any(), any(), any(), any(), any());
	}

	@Test
	void reportsMissingWhenTheQuarantineCopyIsGone(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = tmp.resolve("trash").resolve("exec-1").resolve("10__a.jpg");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		Execution row = claimedRow();

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(movement.getMovementPublicId()), null, row, owning());

		Assertions.assertThat(result.items().get(0).outcome())
				.isEqualTo(RestoreOutcome.MISSING_IN_QUARANTINE.name());

		// The failure names the file, which is what the executions screen lists.
		verify(restoreLog).recordFailure(eq(row), eq(quarantine), eq(ExecutionErrorType.FILE_NOT_FOUND), any());
		verify(persistence, never()).persistRestore(anyLong(), any(), any(), any(), any(), any());
	}

	/**
	 * A worker that died between moving the file back and recording it.
	 *
	 * <p>
	 * The quarantine copy is gone and the file is at the destination the
	 * operation named, which is what a finished restore looks like - except that
	 * the operation is still pending. Told apart from a file that vanished by the
	 * digest the catalog already holds: these are the bytes it is about. Finishing
	 * it means recording it, not moving anything a second time.
	 */
	@Test
	void aRestoreWhoseFileAlreadyLeftQuarantineIsRecordedNotRepeated(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path destination = Files.writeString(origin.resolve("a.jpg"), "content");
		Path quarantine = tmp.resolve("trash").resolve("exec-1").resolve("10__a.jpg");

		Movement movement = quarantineMovement(destination, quarantine);

		movement.getCatalogFile().setSha256(new FileHashService().sha256(destination));

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId()))
				.thenReturn(Optional.of(movement));

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(movement.getMovementPublicId()), null,
				claimedRow(), owning());

		Assertions.assertThat(result.items().get(0).outcome()).isEqualTo(RestoreOutcome.RESTORED.name());
		Assertions.assertThat(result.restored()).isEqualTo(1);

		// Recorded under the identity reserved before the file moved, and with no
		// digest: this attempt moved nothing, so it proved nothing about the bytes.
		// The move here is the real one - had it been attempted, a quarantine copy
		// that is not there could not have produced anything but a failure.
		verify(persistence).persistRestore(anyLong(), any(), any(), eq(quarantine), eq(destination), isNull());

		Assertions.assertThat(Files.readString(destination)).isEqualTo("content");
	}

	/**
	 * The same crash window over a file nobody ever hashed. Hashing is opt-in on a
	 * scan and deliberately skipped for archives wearing a media extension, so this
	 * is an ordinary state - and without a digest there is no safe evidence that
	 * what is at the destination is this file. Nothing is adopted, and the
	 * operation stays for somebody to look at.
	 */
	@Test
	void aRestoreCannotBeResumedForAFileNobodyHashed(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path destination = Files.writeString(origin.resolve("a.jpg"), "content");
		Path quarantine = tmp.resolve("trash").resolve("exec-1").resolve("10__a.jpg");

		Movement movement = quarantineMovement(destination, quarantine);

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId()))
				.thenReturn(Optional.of(movement));

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(movement.getMovementPublicId()), null,
				claimedRow(), owning());

		Assertions.assertThat(result.items().get(0).outcome())
				.isEqualTo(RestoreOutcome.MISSING_IN_QUARANTINE.name());

		verify(persistence, never()).persistRestore(anyLong(), any(), any(), any(), any(), any());
	}

	@Test
	void refusesToRestoreWhenTheQuarantineCopyIsNotAPhysicalFile(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));

		// A .lnk shortcut in quarantine (rejected by PhysicalFilePolicy): the restore
		// must be refused, exactly like the forward path refuses to quarantine a
		// link/shortcut.
		Path quarantine = writeQuarantineCopy(tmp, "10__a.lnk", "shortcut-bytes");

		Movement movement = quarantineMovement(origin.resolve("a.lnk"), quarantine);

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(movement.getMovementPublicId()), null, claimedRow(),
				owning());

		Assertions.assertThat(result.errors()).isEqualTo(1);
		Assertions.assertThat(Files.exists(quarantine)).isTrue();

		verify(persistence, never()).persistRestore(anyLong(), any(), any(), any(), any(), any());
	}

	/**
	 * The quarantine copy is still there and the operation is not: an earlier
	 * attempt settled it. Moving the file now would be carrying out an operation
	 * somebody already closed, under a record that says it is finished.
	 */
	@Test
	void aRestoreWhoseOperationWasAlreadySettledIsRefused(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId()))
				.thenReturn(Optional.of(movement));
		when(movementWriter.prepare(anyLong(), anyList())).thenReturn(List.of(PreparedMovements.settled(1L,
				movement.getCatalogFile().getId(), quarantine, origin.resolve("a.jpg"))));

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(movement.getMovementPublicId()), null,
				claimedRow(), owning());

		Assertions.assertThat(result.items().get(0).outcome()).isEqualTo(RestoreOutcome.ERROR.name());
		Assertions.assertThat(quarantine).as("nothing was moved under a closed operation").exists();

		verify(persistence, never()).persistRestore(anyLong(), any(), any(), any(), any(), any());
	}

	@Test
	void reportsAnErrorForAnIdThatNamesNoMovement() {
		UUID unknown = UUID.randomUUID();

		when(movementRepository.findByMovementPublicId(unknown)).thenReturn(Optional.empty());

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(unknown), null, claimedRow(), owning());

		Assertions.assertThat(result.errors()).isEqualTo(1);
		Assertions.assertThat(result.success()).isFalse();
	}

	/**
	 * A movement that was already undone is no longer MOVED, so it is not a
	 * quarantine item any more even though its reason still says so.
	 */
	@Test
	void refusesAMovementThatIsNoLongerInTheMovedStatus(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		movement.setStatus(MovementStatus.UNDONE);

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(movement.getMovementPublicId()), null, claimedRow(),
				owning());

		Assertions.assertThat(result.errors()).isEqualTo(1);

		verify(persistence, never()).persistRestore(anyLong(), any(), any(), any(), any(), any());
	}

	/**
	 * Status alone is not enough: a movement can be MOVED and still not be a
	 * quarantine - a plain organization move, for instance - and restoring one of
	 * those would put a file back where nobody asked.
	 */
	@Test
	void refusesAMovementWhoseReasonIsNotQuarantine(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		movement.setReason(MovementReason.NONE);

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(movement.getMovementPublicId()), null, claimedRow(),
				owning());

		Assertions.assertThat(result.errors()).isEqualTo(1);
	}

	@Test
	void rollsTheFileBackToQuarantineWhenTheCatalogUpdateFails(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path original = origin.resolve("a.jpg");
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(original, quarantine);

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId())).thenReturn(Optional.of(movement));
		doThrow(new IllegalStateException("db down")).when(persistence).persistRestore(anyLong(), any(), any(), any(),
				any(), any());

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(movement.getMovementPublicId()), null, claimedRow(),
				owning());

		Assertions.assertThat(result.errors()).isEqualTo(1);
		Assertions.assertThat(Files.exists(quarantine)).isTrue();
		Assertions.assertThat(Files.exists(original)).isFalse();
	}

	@Test
	void leavesTheFileOrphanedAtTheDestinationWhenCatalogAndRollbackBothFail(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path original = origin.resolve("a.jpg");
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(original, quarantine);

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId())).thenReturn(Optional.of(movement));
		// The catalog update fails AND re-creates the quarantine copy, so the physical
		// roll-back (which never overwrites) cannot move the file back to quarantine.
		doAnswer(_ -> {
			Files.writeString(quarantine, "blocker");

			throw new IllegalStateException("db down");
		}).when(persistence).persistRestore(anyLong(), any(), any(), any(), any(), any());

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(movement.getMovementPublicId()), null, claimedRow(),
				owning());

		Assertions.assertThat(result.errors()).isEqualTo(1);

		// Roll-back could not put it back, so the restored file stays orphaned at the
		// origin.
		Assertions.assertThat(Files.exists(original)).isTrue();
		Assertions.assertThat(Files.readString(quarantine)).isEqualTo("blocker");
	}

	/**
	 * The same protection that stopped a delete while a conversion held the
	 * quarantine folder: the restore says so instead of touching a path somebody
	 * else is using.
	 */
	@Test
	void reportsLockedWhenAnotherOperationHoldsThePath(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		OperationLockService lockService = mock(OperationLockService.class);

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId())).thenReturn(Optional.of(movement));
		when(lockService.acquire(any(ExecutionType.class), any(Path.class), any(Path.class)))
				.thenThrow(new OperationLockException("busy"));

		QuarantineService locked = new QuarantineService(movementRepository, movementWriter, new FileHashService(), persistence,
				new SecureLibraryFiles(new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()),
						pathRegistry), pathRegistry),
				lockService, executionStopReason, restoreLog, eligibilityAnnouncer);

		QuarantineRestoreBatchResult result = locked.restoreMany(List.of(movement.getMovementPublicId()), null, claimedRow(),
				owning());

		Assertions.assertThat(result.items().get(0).outcome()).isEqualTo(RestoreOutcome.LOCKED.name());
		Assertions.assertThat(Files.exists(quarantine)).isTrue();
	}

	@Test
	void reportsAnErrorWhenTheSecureMoveFails(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		LibraryFileMutations failingMove = mock(LibraryFileMutations.class);

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId())).thenReturn(Optional.of(movement));
		doThrow(new IOException("disk full")).when(failingMove).move(any(), any(), anyBoolean(), any());

		QuarantineService failing = new QuarantineService(movementRepository, movementWriter, new FileHashService(), persistence, failingMove,
				GrantingOperationLocks.granting(), executionStopReason, restoreLog, eligibilityAnnouncer);

		QuarantineRestoreBatchResult result = failing.restoreMany(List.of(movement.getMovementPublicId()), null, claimedRow(),
				owning());

		Assertions.assertThat(result.errors()).isEqualTo(1);

		verify(persistence, never()).persistRestore(anyLong(), any(), any(), any(), any(), any());
	}

	/**
	 * The worst case of a failed restore: the move left the file at the destination
	 * and putting it back failed too. Nothing is silently half-done - the item is
	 * reported as an error and the log names the file to recover.
	 */
	@Test
	void reportsAnErrorWhenNeitherTheMoveNorTheRollbackSucceeded(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		LibraryFileMutations failing = mock(LibraryFileMutations.class);

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId())).thenReturn(Optional.of(movement));
		doAnswer(_ -> {
			// The move physically happened and then failed its verify.
			Files.move(quarantine, origin.resolve("a.jpg"));

			throw new MoveIntegrityException("sha mismatch");
		}).when(failing).move(any(), any(), anyBoolean(), any());
		when(failing.rollback(any(), any())).thenReturn(false);

		QuarantineService orphaning = new QuarantineService(movementRepository, movementWriter, new FileHashService(), persistence, failing,
				GrantingOperationLocks.granting(), executionStopReason, restoreLog, eligibilityAnnouncer);

		QuarantineRestoreBatchResult result = orphaning.restoreMany(List.of(movement.getMovementPublicId()), null,
				claimedRow(), owning());

		Assertions.assertThat(result.errors()).isEqualTo(1);

		verify(failing).rollback(any(), any());
	}

	@Test
	void aSelectionOfNothingIsANoOp() {
		QuarantineRestoreBatchResult empty = service.restoreMany(List.of(), null, claimedRow(), owning());

		QuarantineRestoreBatchResult nullSelection = service.restoreMany(null, null, claimedRow(), owning());

		Assertions.assertThat(empty.total()).isZero();
		Assertions.assertThat(empty.restored()).isZero();
		Assertions.assertThat(nullSelection.total()).isZero();
		Assertions.assertThat(nullSelection.success()).isTrue();
	}

	/**
	 * The batch tally has to account for every outcome kind, not just the happy
	 * ones - an unknown id lands in errors and flips the batch to unsuccessful.
	 */
	@Test
	void countsOriginMissingAndErrorsSeparately(@TempDir Path tmp) throws Exception {
		Path quarantine = writeQuarantineCopy(tmp, "10__gone.jpg", "content");

		Movement originGone = quarantineMovement(tmp.resolve("vanished").resolve("gone.jpg"), quarantine);

		UUID unknown = UUID.randomUUID();

		when(movementRepository.findByMovementPublicId(originGone.getMovementPublicId())).thenReturn(Optional.of(originGone));
		when(movementRepository.findByMovementPublicId(unknown)).thenReturn(Optional.empty());

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(originGone.getMovementPublicId(), unknown), null,
				claimedRow(), owning());

		Assertions.assertThat(result.total()).isEqualTo(2);
		Assertions.assertThat(result.originMissing()).isEqualTo(1);
		Assertions.assertThat(result.errors()).isEqualTo(1);
		Assertions.assertThat(result.success()).isFalse();
	}

	/**
	 * A cancel is a person asking the batch to stop. What it already put back stays
	 * put back - those files were moved under the locks and verified byte for byte
	 * - and the row says so rather than claiming the whole selection was seen.
	 */
	@Test
	void stopsWhereItIsWhenSomebodyCancelsTheBatch(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));

		Movement first = quarantineMovement(origin.resolve("a.jpg"), writeQuarantineCopy(tmp, "10__a.jpg", "one"));
		Movement second = quarantineMovement(origin.resolve("b.jpg"), writeQuarantineCopy(tmp, "11__b.jpg", "two"));

		Execution row = claimedRow();

		when(movementRepository.findByMovementPublicId(first.getMovementPublicId())).thenReturn(Optional.of(first));
		when(executionCancellationService.isCancelled(77L)).thenReturn(false, true);

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(first.getMovementPublicId(), second.getMovementPublicId()),
				null, row, owning());

		Assertions.assertThat(result.restored()).isEqualTo(1);
		Assertions.assertThat(Files.exists(origin.resolve("a.jpg"))).isTrue();
		Assertions.assertThat(Files.exists(origin.resolve("b.jpg"))).isFalse();

		verify(restoreLog).stop(eq(ownership), eq(ExecutionStatus.CANCELLED), eq(2), eq(1), eq(0), eq(0), any());
		verify(restoreLog, never()).finish(any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
	}

	/**
	 * Losing the locks is not a failure of the work: the batch stops where it is
	 * and the row says interrupted, so nobody reads an error for the moment it was
	 * standing in.
	 */
	@Test
	void stopsAsInterruptedWhenItNoLongerOwnsThePaths(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), writeQuarantineCopy(tmp, "10__a.jpg", "one"));

		ExecutionOwnership lost = mock(ExecutionOwnership.class);

		Execution row = claimedRow();

		doThrow(new OwnershipLostException("the lease went away")).when(lost).assertMayGoOnWorking();

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(movement.getMovementPublicId()), null, row, lost);

		Assertions.assertThat(result.restored()).isZero();
		Assertions.assertThat(Files.exists(origin.resolve("a.jpg"))).isFalse();

		verify(restoreLog).stop(eq(lost), eq(ExecutionStatus.INTERRUPTED), eq(1), eq(0), eq(0), eq(0), any());
	}

	/**
	 * A restore moves user files back into the library, so it closes the row it was
	 * handed with what actually happened - which is what the executions screen
	 * shows once the worker is done.
	 */
	@Test
	void closesTheExecutionItWasHandedWithWhatHappened(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		Execution row = claimedRow();

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId())).thenReturn(Optional.of(movement));

		service.restoreMany(List.of(movement.getMovementPublicId()), null, row, owning());

		verify(restoreLog).finish(eq(ownership), eq(1), eq(1), eq(0), eq(0), any());
		verify(persistence).persistRestore(eq(row.getId()), any(), any(), any(), any(), any());
	}

	/** An item left waiting for a decision is not a failure, nor counted as one. */
	@Test
	void anItemWaitingForADecisionIsClosedAsUnrestoredNotAsAnError(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path original = Files.writeString(origin.resolve("a.jpg"), "existing");
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(original, quarantine);

		when(movementRepository.findByMovementPublicId(movement.getMovementPublicId())).thenReturn(Optional.of(movement));

		service.restoreMany(List.of(movement.getMovementPublicId()), null, claimedRow(), owning());

		verify(restoreLog).finish(any(), eq(1), eq(0), eq(1), eq(0), any());
	}

	/**
	 * A crash mid-loop must not leave the row open: the application reads an
	 * unfinished execution as the operation currently running.
	 */
	@Test
	void aCrashMidLoopStillClosesTheExecution() {
		UUID movementId = UUID.randomUUID();

		Execution row = claimedRow();

		List<UUID> selection = List.of(movementId);

		when(movementRepository.findByMovementPublicId(movementId)).thenThrow(new IllegalStateException("db down"));

		Assertions.assertThatThrownBy(() -> service.restoreMany(selection, null, row, ownership))
				.isInstanceOf(IllegalStateException.class);

		verify(restoreLog).fail(eq(ownership), any());
		verify(restoreLog, never()).finish(any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
	}

	/**
	 * The row a worker claimed and the ownership of the paths it locked: the
	 * restore is handed both rather than opening the first and doing without the
	 * second.
	 */
	private Execution claimedRow() {
		return Execution.builder().id(77L).executionType(ExecutionType.QUARANTINE_RESTORE)
				.status(ExecutionStatus.RUNNING).build();
	}

	/**
	 * The taking every write about the row is made under. A real one rather than a
	 * stub, so a write refused for having lost its turn is refused here too.
	 */
	private final ExecutionOwnership ownership = Takings.owning(1L);

	private ExecutionOwnership owning() {
		return ownership;
	}

	private Path writeQuarantineCopy(Path tmp, String name, String content) throws Exception {
		Path folder = Files.createDirectories(tmp.resolve("trash").resolve("exec-1"));

		return Files.writeString(folder.resolve(name), content);
	}

	private Movement quarantineMovement(Path original, Path quarantine) {
		// A real entry, because the restore keys the operations it prepares by the id
		// of the file each one is for - and a mock has none.
		CatalogFile catalogFile = CatalogFiles.at(nextCatalogFileId++, original);

		// Quarantine is not a lifecycle of its own: what says the file is in
		// quarantine is the operation that put it there, over an entry the catalog
		// already carries as deleted. Restoring one that is not is refused.
		catalogFile.markDeleted();

		return Movement.builder().movementPublicId(UUID.randomUUID()).catalogFile(catalogFile)
				.requestedSourcePath(PathUtils.normalize(original)).requestedTargetPath(PathUtils.normalize(quarantine))
				.status(MovementStatus.MOVED).reason(MovementReason.DUPLICATE_QUARANTINED).movedAt(Instant.now())
				.build();
	}
}