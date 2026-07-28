package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateDeletionPersistence;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.MoveIntegrityException;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationMoveVerifier;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureFileMove;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineItemResponse;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreBatchResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreOptions;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.ConflictResolution;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.RestoreOutcome;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

class QuarantineServiceTest {

	private final SelfWrittenPathRegistry pathRegistry = new SelfWrittenPathRegistry(Clock.systemDefaultZone());
	private final MovementRepository movementRepository = mock(MovementRepository.class);
	private final DuplicateDeletionPersistence persistence = mock(DuplicateDeletionPersistence.class);
	private final QuarantineRestoreLog restoreLog = mock(QuarantineRestoreLog.class);
	private final QuarantineService service = new QuarantineService(movementRepository, persistence,
			new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()), pathRegistry),
			new OperationLockService(), restoreLog);

	@Test
	void listsQuarantinedFilesWithLiveOriginAndConflictFlags(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		when(movementRepository.findByStatusAndReasonInOrderByIdDesc(eq(MovementStatus.MOVED),
				eq(QuarantineConstants.QUARANTINED_REASONS), any())).thenReturn(new PageImpl<>(List.of(movement)));

		List<QuarantineItemResponse> items = service.list(PageRequest.of(0, 50)).getContent();

		Assertions.assertThat(items).hasSize(1);

		QuarantineItemResponse item = items.get(0);

		Assertions.assertThat(item.fileName()).isEqualTo("a.jpg");
		Assertions.assertThat(item.presentInQuarantine()).isTrue();
		Assertions.assertThat(item.originFolderExists()).isTrue();
		Assertions.assertThat(item.conflict()).isFalse();
	}

	@Test
	void restoresFileBackToOriginalPath(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path original = origin.resolve("a.jpg");
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(original, quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.RESTORED.name());
		Assertions.assertThat(result.success()).isTrue();
		Assertions.assertThat(Files.exists(original)).isTrue();
		Assertions.assertThat(Files.exists(quarantine)).isFalse();

		verify(persistence).applyRestore(eq(movement), any(), any());
	}

	@Test
	void blocksWhenDestinationAlreadyHasAFile(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path original = Files.writeString(origin.resolve("a.jpg"), "existing");
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(original, quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.CONFLICT.name());
		Assertions.assertThat(Files.exists(quarantine)).isTrue();
		Assertions.assertThat(Files.readString(original)).isEqualTo("existing");

		verify(persistence, never()).applyRestore(any(), any(), any());
	}

	@Test
	void restoresUnderNumberedNameWhenAskedToRename(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path original = Files.writeString(origin.resolve("a.jpg"), "existing");
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(original, quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(),
				new QuarantineRestoreOptions(null, ConflictResolution.RENAME));

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.RESTORED.name());
		Assertions.assertThat(Files.exists(original)).isTrue();
		Assertions.assertThat(Files.exists(origin.resolve("a (1).jpg"))).isTrue();
		Assertions.assertThat(Files.exists(quarantine)).isFalse();
	}

	@Test
	void reportsOriginMissingWhenOriginalFolderIsGone(@TempDir Path tmp) throws Exception {
		Path original = tmp.resolve("gone").resolve("a.jpg");
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(original, quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.ORIGIN_MISSING.name());
		Assertions.assertThat(Files.exists(quarantine)).isTrue();

		verify(persistence, never()).applyRestore(any(), any(), any());
	}

	@Test
	void restoresIntoChosenAlternateFolder(@TempDir Path tmp) throws Exception {
		Path original = tmp.resolve("gone").resolve("a.jpg");
		Path alternate = Files.createDirectories(tmp.resolve("elsewhere"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(original, quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(),
				new QuarantineRestoreOptions(alternate, ConflictResolution.BLOCK));

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.RESTORED.name());
		Assertions.assertThat(Files.exists(alternate.resolve("a.jpg"))).isTrue();
		Assertions.assertThat(Files.exists(quarantine)).isFalse();
	}

	@Test
	void reportsMissingWhenQuarantineCopyIsGone(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = tmp.resolve("trash").resolve("exec-1").resolve("10__a.jpg");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.MISSING_IN_QUARANTINE.name());

		verify(persistence, never()).applyRestore(any(), any(), any());
	}

	@Test
	void refusesToRestoreWhenTheQuarantineCopyIsNotAPhysicalFile(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		// A .lnk shortcut in quarantine (rejected by PhysicalFilePolicy): the restore
		// must be
		// refused, exactly like the forward path refuses to quarantine a link/shortcut.
		Path quarantine = writeQuarantineCopy(tmp, "10__a.lnk", "shortcut-bytes");

		Movement movement = quarantineMovement(origin.resolve("a.lnk"), quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.ERROR.name());
		// The item stays in quarantine; it is never "restored" as a link into the
		// library.
		Assertions.assertThat(Files.exists(quarantine)).isTrue();

		verify(persistence, never()).applyRestore(any(), any(), any());
	}

	@Test
	void batchRestoreCountsRestoredAndConflicts(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path freeOriginal = origin.resolve("free.jpg");
		Path takenOriginal = Files.writeString(origin.resolve("taken.jpg"), "existing");

		Path freeQuarantine = writeQuarantineCopy(tmp, "10__free.jpg", "one");
		Path takenQuarantine = writeQuarantineCopy(tmp, "11__taken.jpg", "two");

		Movement free = quarantineMovement(freeOriginal, freeQuarantine);
		Movement taken = quarantineMovement(takenOriginal, takenQuarantine);

		when(movementRepository.findByPublicId(free.getPublicId())).thenReturn(Optional.of(free));
		when(movementRepository.findByPublicId(taken.getPublicId())).thenReturn(Optional.of(taken));

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(free.getPublicId(), taken.getPublicId()));

		Assertions.assertThat(result.total()).isEqualTo(2);
		Assertions.assertThat(result.restored()).isEqualTo(1);
		Assertions.assertThat(result.conflicts()).isEqualTo(1);
		Assertions.assertThat(Files.exists(freeOriginal)).isTrue();
		Assertions.assertThat(Files.exists(takenQuarantine)).isTrue();
	}

	@Test
	void returnsErrorWhenMovementNotFound() {
		UUID id = UUID.randomUUID();

		when(movementRepository.findByPublicId(id)).thenReturn(Optional.empty());

		QuarantineRestoreResult result = service.restore(id, QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.ERROR.name());
		Assertions.assertThat(result.success()).isFalse();
	}

	@Test
	void returnsErrorWhenItemIsNoLongerQuarantined(@TempDir Path tmp) throws Exception {
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(tmp.resolve("library").resolve("a.jpg"), quarantine);

		movement.setStatus(MovementStatus.UNDONE);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.ERROR.name());

		verify(persistence, never()).applyRestore(any(), any(), any());
	}

	@Test
	void keepsFileInQuarantineWhenResolutionIsSkip(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(),
				new QuarantineRestoreOptions(null, ConflictResolution.SKIP));

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.SKIPPED.name());
		Assertions.assertThat(Files.exists(quarantine)).isTrue();

		verify(persistence, never()).applyRestore(any(), any(), any());
	}

	@Test
	void rollsFileBackToQuarantineWhenCatalogUpdateFails(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path original = origin.resolve("a.jpg");
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(original, quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));
		doThrow(new IllegalStateException("db down")).when(persistence).applyRestore(any(), any(), any());

		QuarantineRestoreResult result = service.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.ERROR.name());
		Assertions.assertThat(Files.exists(quarantine)).isTrue();
		Assertions.assertThat(Files.exists(original)).isFalse();
	}

	@Test
	void leavesFileOrphanedAtDestinationWhenCatalogAndRollbackBothFail(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path original = origin.resolve("a.jpg");
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(original, quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));
		// The catalog update fails AND re-creates the quarantine copy, so the physical
		// roll-back (which never overwrites) cannot move the file back to quarantine.
		doAnswer(_ -> {
			Files.writeString(quarantine, "blocker");
			throw new IllegalStateException("db down");
		}).when(persistence).applyRestore(any(), any(), any());

		QuarantineRestoreResult result = service.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.ERROR.name());
		// Roll-back could not put it back, so the restored file stays orphaned at the
		// origin.
		Assertions.assertThat(Files.exists(original)).isTrue();
		Assertions.assertThat(Files.readString(quarantine)).isEqualTo("blocker");
	}

	@Test
	void reportsLockedWhenAnotherOperationHoldsThePath(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		OperationLockService lockService = mock(OperationLockService.class);

		when(lockService.acquire(any(ExecutionType.class), any(Path.class), any(Path.class)))
				.thenThrow(new OperationLockException("busy"));

		QuarantineService locked = new QuarantineService(movementRepository, persistence,
				new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()), pathRegistry), lockService,
				restoreLog);

		QuarantineRestoreResult result = locked.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.LOCKED.name());
		Assertions.assertThat(Files.exists(quarantine)).isTrue();
	}

	@Test
	void reportsErrorWhenTheSecureMoveFails(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		SecureFileMove failingMove = mock(SecureFileMove.class);

		doThrow(new IOException("disk full")).when(failingMove).move(any(), any(), anyBoolean());

		QuarantineService failing = new QuarantineService(movementRepository, persistence, failingMove,
				new OperationLockService(), restoreLog);

		QuarantineRestoreResult result = failing.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.ERROR.name());

		verify(persistence, never()).applyRestore(any(), any(), any());
	}

	@Test
	void listFlagsMissingOriginAndFallsBackToDiskSize(@TempDir Path tmp) throws Exception {
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "abcd");

		Movement movement = quarantineMovement(tmp.resolve("gone").resolve("a.jpg"), quarantine);

		when(movement.getCatalogFile().getSizeBytes()).thenReturn(null);

		when(movementRepository.findByStatusAndReasonInOrderByIdDesc(eq(MovementStatus.MOVED),
				eq(QuarantineConstants.QUARANTINED_REASONS), any())).thenReturn(new PageImpl<>(List.of(movement)));

		QuarantineItemResponse item = service.list(PageRequest.of(0, 50)).getContent().get(0);

		Assertions.assertThat(item.originFolderExists()).isFalse();
		Assertions.assertThat(item.presentInQuarantine()).isTrue();
		Assertions.assertThat(item.sizeBytes()).isEqualTo(4L);
		Assertions.assertThat(item.sizeLabel()).isEqualTo("4 B");
	}

	@Test
	void listBuildsMediaUrlsForImages(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "img");

		UUID publicId = UUID.randomUUID();

		Movement movement = imageMovement(origin.resolve("a.jpg"), quarantine, publicId);

		when(movementRepository.findByStatusAndReasonInOrderByIdDesc(eq(MovementStatus.MOVED),
				eq(QuarantineConstants.QUARANTINED_REASONS), any())).thenReturn(new PageImpl<>(List.of(movement)));

		QuarantineItemResponse item = service.list(PageRequest.of(0, 50)).getContent().get(0);

		Assertions.assertThat(item.image()).isTrue();
		Assertions.assertThat(item.mediaPublicId()).isEqualTo(publicId);
		Assertions.assertThat(item.previewUrl()).isEqualTo("/api/media/" + publicId + "/content");
	}

	@Test
	void restoreManyRestoresEachSelectedFile(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(movement.getPublicId()));

		Assertions.assertThat(result.total()).isEqualTo(1);
		Assertions.assertThat(result.restored()).isEqualTo(1);
		Assertions.assertThat(Files.exists(origin.resolve("a.jpg"))).isTrue();
	}

	@Test
	void restoreManyWithNoSelectionIsNoOp() {
		QuarantineRestoreBatchResult result = service.restoreMany(List.of());

		Assertions.assertThat(result.total()).isZero();
		Assertions.assertThat(result.restored()).isZero();
	}

	@Test
	void nullOptionsShouldFallBackToTheSafeDefaults(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(), null);

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.RESTORED.name());
		Assertions.assertThat(origin.resolve("a.jpg")).exists();
	}

	/**
	 * A movement that was already undone is no longer MOVED, so it is not a
	 * quarantine item any more even though its reason still says so.
	 */
	@Test
	void shouldRefuseAMovementThatIsNoLongerInTheMovedStatus(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		movement.setStatus(MovementStatus.UNDONE);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(),
				QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.ERROR.name());
	}

	/**
	 * Status alone is not enough: a movement can be MOVED and still not be a
	 * quarantine - a plain organization move, for instance - and restoring one
	 * of those would put a file back where nobody asked.
	 */
	@Test
	void shouldRefuseAMovementWhoseReasonIsNotQuarantine(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		movement.setReason(MovementReason.NONE);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(),
				QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.ERROR.name());
	}

	/**
	 * The same protection that stopped a delete while a conversion held the
	 * quarantine folder: the restore says so instead of touching a path somebody
	 * else is using.
	 */
	@Test
	void shouldReportLockedWhenAnotherOperationHoldsThePath(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		OperationLockService lockService = mock(OperationLockService.class);

		when(lockService.acquire(any(), any(Path[].class))).thenThrow(new OperationLockException("busy"));

		QuarantineService locked = new QuarantineService(movementRepository, persistence,
				new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()), pathRegistry), lockService,
				restoreLog);

		QuarantineRestoreResult result = locked.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.LOCKED.name());
		Assertions.assertThat(quarantine).exists();
	}

	/**
	 * The worst case of a failed restore: the move left the file at the
	 * destination and putting it back failed too. Nothing is silently half-done -
	 * the item is reported as an error and the log names the file to recover.
	 */
	@Test
	void shouldReportAnErrorWhenNeitherTheMoveNorTheRollbackSucceeded(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		SecureFileMove failing = mock(SecureFileMove.class);

		doAnswer(_ -> {
			// The move physically happened and then failed its verify.
			Files.move(quarantine, origin.resolve("a.jpg"));

			throw new MoveIntegrityException("sha mismatch");
		}).when(failing).move(any(), any(), anyBoolean());

		when(failing.rollback(any(), any())).thenReturn(false);

		QuarantineService orphaning = new QuarantineService(movementRepository, persistence, failing,
				new OperationLockService(), restoreLog);

		QuarantineRestoreResult result = orphaning.restore(movement.getPublicId(),
				QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.ERROR.name());

		verify(failing).rollback(any(), any());
	}

	@Test
	void restoreManyShouldTreatANullSelectionAsEmpty() {
		QuarantineRestoreBatchResult result = service.restoreMany(null);

		Assertions.assertThat(result.total()).isZero();
		Assertions.assertThat(result.success()).isTrue();
	}

	/**
	 * The batch tally has to account for every outcome kind, not just the happy
	 * ones - an unknown id lands in errors and flips the batch to unsuccessful.
	 */
	@Test
	void batchRestoreShouldCountOriginMissingAndErrorsSeparately(@TempDir Path tmp) throws Exception {
		Path quarantine = writeQuarantineCopy(tmp, "10__gone.jpg", "content");

		Movement originGone = quarantineMovement(tmp.resolve("vanished").resolve("gone.jpg"), quarantine);

		UUID unknown = UUID.randomUUID();

		when(movementRepository.findByPublicId(originGone.getPublicId())).thenReturn(Optional.of(originGone));
		when(movementRepository.findByPublicId(unknown)).thenReturn(Optional.empty());

		QuarantineRestoreBatchResult result = service.restoreMany(List.of(originGone.getPublicId(), unknown));

		Assertions.assertThat(result.total()).isEqualTo(2);
		Assertions.assertThat(result.originMissing()).isEqualTo(1);
		Assertions.assertThat(result.errors()).isEqualTo(1);
		Assertions.assertThat(result.success()).isFalse();
	}

	/**
	 * An extension-less name must not lose its whole filename to the "(1)" suffix
	 * logic, which splits on the last dot.
	 */
	@Test
	void renamingOnConflictShouldHandleANameWithoutAnExtension(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));

		Files.writeString(origin.resolve("README"), "existing");

		Path quarantine = writeQuarantineCopy(tmp, "10__README", "content");

		Movement movement = quarantineMovement(origin.resolve("README"), quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(),
				new QuarantineRestoreOptions(null, ConflictResolution.RENAME));

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.RESTORED.name());
		Assertions.assertThat(origin.resolve("README (1)")).exists();
	}

	/**
	 * Without a catalog record the listing still has to render: the type falls back
	 * to OTHER, there is no media id to build a preview URL from, and the size is
	 * read off the quarantine copy.
	 */
	@Test
	void listShouldRenderAnItemWhoseCatalogRecordIsGone(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__orphan.jpg", "1234567");

		Execution execution = mock(Execution.class);

		when(execution.getPublicId()).thenReturn(UUID.randomUUID());

		Movement movement = Movement.builder().publicId(UUID.randomUUID()).execution(execution)
				.sourcePath(PathUtils.normalize(origin.resolve("orphan.jpg")))
				.targetPath(PathUtils.normalize(quarantine)).status(MovementStatus.MOVED)
				.reason(MovementReason.DUPLICATE_QUARANTINED).movedAt(LocalDateTime.now()).build();

		when(movementRepository.findByStatusAndReasonInOrderByIdDesc(eq(MovementStatus.MOVED),
				eq(QuarantineConstants.QUARANTINED_REASONS), any())).thenReturn(new PageImpl<>(List.of(movement)));

		QuarantineItemResponse item = service.list(PageRequest.of(0, 50)).getContent().get(0);

		Assertions.assertThat(item.mediaPublicId()).isNull();
		Assertions.assertThat(item.previewUrl()).isNull();
		Assertions.assertThat(item.fileType()).isEqualTo("OTHER");
		Assertions.assertThat(item.sizeBytes()).isEqualTo(7L);
	}

	/**
	 * A quarantine copy that vanished leaves nothing to measure, so the screen gets
	 * a null size and the em-dash placeholder instead of a bogus zero.
	 */
	@Test
	void listShouldReportNoSizeWhenNeitherTheCatalogNorTheDiskCanProvideIt(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));

		Execution execution = mock(Execution.class);

		when(execution.getPublicId()).thenReturn(UUID.randomUUID());

		CatalogFile catalogFile = mock(CatalogFile.class);

		when(catalogFile.getFileType()).thenReturn(FileType.PHOTO);
		when(catalogFile.getSizeBytes()).thenReturn(null);

		Movement movement = Movement.builder().publicId(UUID.randomUUID()).execution(execution)
				.catalogFile(catalogFile).sourcePath(PathUtils.normalize(origin.resolve("missing.jpg")))
				.targetPath(PathUtils.normalize(tmp.resolve("trash").resolve("nowhere.jpg")))
				.status(MovementStatus.MOVED).reason(MovementReason.DUPLICATE_QUARANTINED)
				.movedAt(LocalDateTime.now()).build();

		when(movementRepository.findByStatusAndReasonInOrderByIdDesc(eq(MovementStatus.MOVED),
				eq(QuarantineConstants.QUARANTINED_REASONS), any())).thenReturn(new PageImpl<>(List.of(movement)));

		QuarantineItemResponse item = service.list(PageRequest.of(0, 50)).getContent().get(0);

		Assertions.assertThat(item.sizeBytes()).isNull();
		Assertions.assertThat(item.sizeLabel()).isEqualTo("—");
		Assertions.assertThat(item.presentInQuarantine()).isFalse();
	}

	/**
	 * The card decides which viewer to open from these flags, so each previewable
	 * kind has to come back set on its own and nothing else.
	 */
	@Test
	void listShouldFlagEachPreviewableKindOnItsOwn(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));

		Movement video = typedMovement(tmp, origin, "clip.mp4", FileType.VIDEO);
		Movement pdf = typedMovement(tmp, origin, "manual.pdf", FileType.PDF);
		Movement text = typedMovement(tmp, origin, "notes.txt", FileType.TEXT);
		Movement audio = typedMovement(tmp, origin, "song.mp3", FileType.AUDIO);

		when(movementRepository.findByStatusAndReasonInOrderByIdDesc(eq(MovementStatus.MOVED),
				eq(QuarantineConstants.QUARANTINED_REASONS), any()))
				.thenReturn(new PageImpl<>(List.of(video, pdf, text, audio)));

		List<QuarantineItemResponse> items = service.list(PageRequest.of(0, 50)).getContent();

		Assertions.assertThat(items.get(0).video()).isTrue();
		Assertions.assertThat(items.get(0).image()).isFalse();
		Assertions.assertThat(items.get(1).pdf()).isTrue();
		Assertions.assertThat(items.get(2).text()).isTrue();
		Assertions.assertThat(items.get(3).audio()).isTrue();
		Assertions.assertThat(items).allSatisfy(item -> Assertions.assertThat(item.previewUrl()).isNotNull());
	}

	/**
	 * A kind with no viewer gets no preview URL even though the media is perfectly
	 * cataloged - the card then falls back to the generic icon.
	 */
	@Test
	void listShouldNotOfferAPreviewForAKindWithNoViewer(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));

		Movement archive = typedMovement(tmp, origin, "backup.zip", FileType.ZIP);

		when(movementRepository.findByStatusAndReasonInOrderByIdDesc(eq(MovementStatus.MOVED),
				eq(QuarantineConstants.QUARANTINED_REASONS), any())).thenReturn(new PageImpl<>(List.of(archive)));

		QuarantineItemResponse item = service.list(PageRequest.of(0, 50)).getContent().get(0);

		Assertions.assertThat(item.previewUrl()).isNull();
		Assertions.assertThat(item.image()).isFalse();
		Assertions.assertThat(item.video()).isFalse();
	}

	private Movement typedMovement(Path tmp, Path origin, String fileName, FileType fileType) throws Exception {
		Path quarantine = writeQuarantineCopy(tmp, "10__" + fileName, "content");

		Execution execution = mock(Execution.class);

		when(execution.getPublicId()).thenReturn(UUID.randomUUID());

		CatalogFile catalogFile = mock(CatalogFile.class);

		when(catalogFile.getFileType()).thenReturn(fileType);
		when(catalogFile.getSizeBytes()).thenReturn(7L);
		when(catalogFile.getPublicId()).thenReturn(UUID.randomUUID());

		return Movement.builder().publicId(UUID.randomUUID()).execution(execution).catalogFile(catalogFile)
				.sourcePath(PathUtils.normalize(origin.resolve(fileName))).targetPath(PathUtils.normalize(quarantine))
				.status(MovementStatus.MOVED).reason(MovementReason.DUPLICATE_QUARANTINED)
				.movedAt(LocalDateTime.now()).build();
	}

	private Movement imageMovement(Path original, Path quarantine, UUID publicId) {
		Execution execution = mock(Execution.class);

		when(execution.getPublicId()).thenReturn(UUID.randomUUID());

		CatalogFile catalogFile = mock(CatalogFile.class);

		when(catalogFile.getFileType()).thenReturn(FileType.PHOTO);
		when(catalogFile.getSizeBytes()).thenReturn(10L);
		when(catalogFile.getPublicId()).thenReturn(publicId);

		return Movement.builder().publicId(UUID.randomUUID()).execution(execution).catalogFile(catalogFile)
				.sourcePath(PathUtils.normalize(original)).targetPath(PathUtils.normalize(quarantine))
				.status(MovementStatus.MOVED).reason(MovementReason.DUPLICATE_QUARANTINED).movedAt(LocalDateTime.now())
				.build();
	}

	/**
	 * A restore moves user files back into the library, so it is an operation and
	 * has to leave a row on the executions screen - opened before the first file
	 * and closed with what happened.
	 */
	@Test
	void aRestoreOpensAndClosesAnExecutionOfItsOwn(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		Execution restoreExecution = mock(Execution.class);

		when(restoreLog.start(1)).thenReturn(restoreExecution);
		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		service.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		verify(restoreLog).start(1);
		verify(restoreLog).finish(eq(restoreExecution), eq(1), eq(1), eq(0), eq(0), any());
		verify(persistence).applyRestore(eq(movement), any(), eq(restoreExecution));
	}

	/** An item waiting for a decision is not a failure, nor counted as one. */
	@Test
	void anItemWaitingForADecisionIsClosedAsSkippedNotAsAnError(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path original = Files.writeString(origin.resolve("a.jpg"), "existing");
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		Movement movement = quarantineMovement(original, quarantine);

		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		service.restore(movement.getPublicId(), QuarantineRestoreOptions.defaults());

		verify(restoreLog).finish(any(), eq(1), eq(0), eq(1), eq(0), any());
	}

	/** The failure names the file, which is what the execution screen lists. */
	@Test
	void aFileMissingFromQuarantineIsRecordedAgainstTheRestore(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = tmp.resolve("trash").resolve("exec-1").resolve("10__a.jpg");

		Movement movement = quarantineMovement(origin.resolve("a.jpg"), quarantine);

		Execution restoreExecution = mock(Execution.class);

		when(restoreLog.start(1)).thenReturn(restoreExecution);
		when(movementRepository.findByPublicId(movement.getPublicId())).thenReturn(Optional.of(movement));

		QuarantineRestoreResult result = service.restore(movement.getPublicId(),
				QuarantineRestoreOptions.defaults());

		Assertions.assertThat(result.outcome()).isEqualTo(RestoreOutcome.MISSING_IN_QUARANTINE.name());

		verify(restoreLog).recordFailure(eq(restoreExecution), eq(quarantine), eq(ExecutionErrorType.FILE_NOT_FOUND),
				any());
		verify(restoreLog).finish(eq(restoreExecution), eq(1), eq(0), eq(0), eq(1), any());
	}

	/**
	 * A crash mid-loop must not leave the row open: the application reads an
	 * unfinished execution as the operation currently running.
	 */
	@Test
	void aCrashMidLoopStillClosesTheExecution() {
		UUID movementId = UUID.randomUUID();

		Execution restoreExecution = mock(Execution.class);

		when(restoreLog.start(1)).thenReturn(restoreExecution);
		when(movementRepository.findByPublicId(movementId)).thenThrow(new IllegalStateException("db down"));

		QuarantineRestoreOptions options = QuarantineRestoreOptions.defaults();

		Assertions.assertThatThrownBy(() -> service.restore(movementId, options))
				.isInstanceOf(IllegalStateException.class);

		verify(restoreLog).fail(eq(restoreExecution), eq(1), any());
		verify(restoreLog, never()).finish(any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
	}

	private Path writeQuarantineCopy(Path tmp, String name, String content) throws Exception {
		Path folder = Files.createDirectories(tmp.resolve("trash").resolve("exec-1"));

		return Files.writeString(folder.resolve(name), content);
	}

	private Movement quarantineMovement(Path original, Path quarantine) {
		Execution execution = mock(Execution.class);

		when(execution.getPublicId()).thenReturn(UUID.randomUUID());

		CatalogFile catalogFile = mock(CatalogFile.class);

		when(catalogFile.getFileType()).thenReturn(null);
		when(catalogFile.getSizeBytes()).thenReturn(123L);

		return Movement.builder().publicId(UUID.randomUUID()).execution(execution).catalogFile(catalogFile)
				.sourcePath(PathUtils.normalize(original)).targetPath(PathUtils.normalize(quarantine))
				.status(MovementStatus.MOVED).reason(MovementReason.DUPLICATE_QUARANTINED).movedAt(LocalDateTime.now())
				.build();
	}
}